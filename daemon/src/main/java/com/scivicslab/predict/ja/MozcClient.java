package com.scivicslab.predict.ja;

import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client for mozc_server IPC using abstract Unix domain socket + protobuf.
 * Ported from quarkus-llm-ime MozcClient (stripped of Quarkus annotations).
 */
public class MozcClient {

    private static final Logger LOG = Logger.getLogger(MozcClient.class.getName());

    private volatile String socketKey;

    /**
     * Segment result: reading + candidates for one bunsetsu.
     */
    public record SegmentResult(String reading, List<String> candidates) {}

    /**
     * Get segmented conversion from mozc.
     * Returns a list of segments, each with its reading and candidates.
     */
    public List<SegmentResult> getSegments(String hiragana) {
        if (hiragana == null || hiragana.isBlank()) {
            return List.of(new SegmentResult(hiragana, List.of(hiragana)));
        }

        try {
            String key = getSocketKey();
            if (key == null) {
                return List.of(new SegmentResult(hiragana, List.of(hiragana)));
            }

            Output createOut = sendRequest(key, Input.newBuilder()
                    .setType(Input.CommandType.CREATE_SESSION)
                    .build());
            if (createOut == null || createOut.getErrorCode() != Output.ErrorCode.SESSION_SUCCESS) {
                return List.of(new SegmentResult(hiragana, List.of(hiragana)));
            }
            long sessionId = createOut.getId();

            try {
                return segmentInSession(key, sessionId, hiragana);
            } finally {
                sendRequest(key, Input.newBuilder()
                        .setType(Input.CommandType.DELETE_SESSION)
                        .setId(sessionId)
                        .build());
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Mozc segment failed", e);
            socketKey = null;
            return List.of(new SegmentResult(hiragana, List.of(hiragana)));
        }
    }

    private List<SegmentResult> segmentInSession(String key, long sessionId, String hiragana)
            throws IOException {
        // Send hiragana
        sendRequest(key, Input.newBuilder()
                .setType(Input.CommandType.SEND_KEY)
                .setId(sessionId)
                .setKey(KeyEvent.newBuilder()
                        .setSpecialKey(KeyEvent.SpecialKey.TEXT_INPUT)
                        .setKeyString(hiragana))
                .build());

        // Press Space to trigger conversion
        Output convOut = sendRequest(key, Input.newBuilder()
                .setType(Input.CommandType.SEND_KEY)
                .setId(sessionId)
                .setKey(KeyEvent.newBuilder()
                        .setSpecialKey(KeyEvent.SpecialKey.SPACE))
                .build());

        if (convOut == null || !convOut.hasPreedit()) {
            return List.of(new SegmentResult(hiragana, List.of(hiragana)));
        }

        // Build initial segments from preedit
        var segments = new ArrayList<SegmentResult>();
        for (var seg : convOut.getPreedit().getSegmentList()) {
            String value = seg.getValue();
            String reading = seg.hasKey() ? seg.getKey() : value;
            var candidates = new ArrayList<String>();
            candidates.add(value);
            if (!value.equals(reading)) {
                candidates.add(reading);
            }
            segments.add(new SegmentResult(reading, candidates));
        }

        if (segments.isEmpty()) {
            return List.of(new SegmentResult(hiragana, List.of(hiragana)));
        }

        // Press Space again to open candidate window for the focused segment
        Output candOut = sendRequest(key, Input.newBuilder()
                .setType(Input.CommandType.SEND_KEY)
                .setId(sessionId)
                .setKey(KeyEvent.newBuilder()
                        .setSpecialKey(KeyEvent.SpecialKey.SPACE))
                .build());

        // Extract candidates from all_candidate_words
        if (candOut != null && candOut.hasAllCandidateWords()) {
            var candidateWords = candOut.getAllCandidateWords().getCandidatesList();
            if (!candidateWords.isEmpty()) {
                // Candidates are for the focused segment (usually the first one)
                int focusedSeg = 0;
                if (candOut.hasPreedit()) {
                    // Find which segment is highlighted
                    var preeditSegs = candOut.getPreedit().getSegmentList();
                    for (int i = 0; i < preeditSegs.size(); i++) {
                        if (preeditSegs.get(i).hasAnnotation()
                            && preeditSegs.get(i).getAnnotation()
                                == org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Preedit.Segment.Annotation.HIGHLIGHT) {
                            focusedSeg = i;
                            break;
                        }
                    }
                }

                if (focusedSeg < segments.size()) {
                    var existing = segments.get(focusedSeg);
                    var enriched = new ArrayList<>(existing.candidates());
                    for (var cw : candidateWords) {
                        String val = cw.getValue();
                        if (!enriched.contains(val)) {
                            enriched.add(val);
                        }
                    }
                    segments.set(focusedSeg,
                            new SegmentResult(existing.reading(), enriched));
                }
            }
        }

        return segments;
    }

    private Output sendRequest(String key, Input input) throws IOException {
        String abstractPath = "\0tmp/.mozc." + key + ".session";
        UnixSocketAddress addr = new UnixSocketAddress(abstractPath);

        try (UnixSocketChannel channel = UnixSocketChannel.open(addr)) {
            byte[] data = input.toByteArray();
            ByteBuffer sendBuf = ByteBuffer.wrap(data);
            while (sendBuf.hasRemaining()) {
                channel.write(sendBuf);
            }
            channel.shutdownOutput();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ByteBuffer recvBuf = ByteBuffer.allocate(65536);
            while (true) {
                recvBuf.clear();
                int n = channel.read(recvBuf);
                if (n <= 0) break;
                recvBuf.flip();
                byte[] chunk = new byte[recvBuf.remaining()];
                recvBuf.get(chunk);
                baos.write(chunk);
            }

            byte[] responseBytes = baos.toByteArray();
            if (responseBytes.length == 0) {
                return null;
            }
            return Output.parseFrom(responseBytes);
        }
    }

    private String getSocketKey() {
        if (socketKey != null) {
            return socketKey;
        }

        Path[] paths = {
            Path.of(System.getProperty("user.home"), ".config", "mozc", ".session.ipc"),
            Path.of(System.getProperty("user.home"), ".mozc", ".session.ipc"),
        };

        for (Path p : paths) {
            if (Files.exists(p)) {
                try {
                    byte[] bytes = Files.readAllBytes(p);
                    var info = mozc.ipc.Ipc.IPCPathInfo.parseFrom(bytes);
                    if (info.hasKey() && !info.getKey().isEmpty()) {
                        socketKey = info.getKey();
                        LOG.info("Mozc socket key: " + socketKey);
                        return socketKey;
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to read mozc session.ipc from " + p, e);
                }
            }
        }

        LOG.warning("Mozc .session.ipc not found; mozc candidates unavailable");
        return null;
    }
}
