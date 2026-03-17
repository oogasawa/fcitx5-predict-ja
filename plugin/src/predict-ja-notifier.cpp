#include <curl/curl.h>
#include <fcitx-utils/log.h>
#include <fcitx/addonfactory.h>
#include <fcitx/addoninstance.h>
#include <fcitx/addonmanager.h>
#include <fcitx/inputcontext.h>
#include <fcitx/instance.h>
#include <string>
#include <thread>

namespace {

using namespace fcitx;

// Simple async HTTP POST to notify daemon of committed text.
// Fire-and-forget: errors are logged but do not affect the user.
class DaemonNotifier {
public:
    DaemonNotifier(const std::string &daemonUrl)
        : daemonUrl_(daemonUrl) {}

    void notifyCommit(const std::string &preedit,
                      const std::string &committed) {
        // Run in a detached thread to avoid blocking the input pipeline
        std::thread([this, preedit, committed]() {
            CURL *curl = curl_easy_init();
            if (!curl) return;

            std::string url = daemonUrl_ + "/api/record";
            std::string body = "{\"input\":\"";
            body += escapeJson(preedit);
            body += "\",\"output\":\"";
            body += escapeJson(committed);
            body += "\",\"context\":\"\"}";

            struct curl_slist *headers = nullptr;
            headers = curl_slist_append(headers, "Content-Type: application/json");

            curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
            curl_easy_setopt(curl, CURLOPT_POSTFIELDS, body.c_str());
            curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
            curl_easy_setopt(curl, CURLOPT_TIMEOUT, 2L);
            curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);

            CURLcode res = curl_easy_perform(curl);
            if (res != CURLE_OK) {
                FCITX_WARN() << "predict-ja: daemon notify failed: "
                             << curl_easy_strerror(res);
            }

            curl_slist_free_all(headers);
            curl_easy_cleanup(curl);
        }).detach();
    }

private:
    std::string daemonUrl_;

    static std::string escapeJson(const std::string &s) {
        std::string result;
        result.reserve(s.size());
        for (char c : s) {
            switch (c) {
            case '"':  result += "\\\""; break;
            case '\\': result += "\\\\"; break;
            case '\n': result += "\\n"; break;
            case '\t': result += "\\t"; break;
            default:   result += c; break;
            }
        }
        return result;
    }
};

class PredictJaNotifier : public AddonInstance {
public:
    PredictJaNotifier(Instance *instance)
        : instance_(instance),
          notifier_("http://localhost:8190") {
        // Listen for commit events on all input contexts
        commitConn_ = instance->watchEvent(
            EventType::InputContextCommitString,
            EventWatcherPhase::PostInputMethod,
            [this](Event &event) {
                auto &commitEvent =
                    static_cast<CommitStringEvent &>(event);
                auto *ic = commitEvent.inputContext();

                std::string committed = commitEvent.text();
                // We don't have the preedit at this point,
                // so send committed as both input and output.
                // The daemon will use this for direct knowledge base addition.
                notifier_.notifyCommit(committed, committed);
            });

        FCITX_INFO() << "predict-ja-notifier loaded";
    }

private:
    Instance *instance_;
    DaemonNotifier notifier_;
    std::unique_ptr<HandlerTableEntry<EventHandler>> commitConn_;
};

class PredictJaNotifierFactory : public AddonFactory {
public:
    AddonInstance *create(AddonManager *manager) override {
        return new PredictJaNotifier(manager->instance());
    }
};

} // namespace

FCITX_ADDON_FACTORY(PredictJaNotifierFactory)
