// Builds the "c8 add profile dev" snippet shown on the add/edit API client pages, from
// C8CTL_BASE_URL/C8CTL_OAUTH_URL (inlined by the page via Thymeleaf) plus the current #clientId
// and #secretValue field values. Re-run on load and on every #clientId input so typing a client
// ID (add-client.html) updates the snippet live; edit-client.html's #clientId is read-only, so
// there the snippet is effectively just rendered once from the server-supplied values.
//
// Also wires add-client.html's client-side-only "Generate new" button (#regenerateSecretButton):
// unlike edit-client.html's same-labeled button, which regenerates+persists via a server
// round-trip (the client already exists there), a not-yet-created client has nothing to persist
// to, so this one just swaps in a fresh random secret in the browser, in the same base64url,
// 32-byte shape as AdminClientController.generateSecret() - not present on edit-client.html, so
// this is a no-op there.
(function () {
    function currentValue(id, placeholder) {
        var el = document.getElementById(id);
        return (el && el.value) || placeholder;
    }

    function updateC8ctlConfig() {
        var target = document.getElementById("c8ctlConfig");
        if (!target) {
            return;
        }
        var clientId = currentValue("clientId", "<client-id>");
        var secret = currentValue("secretValue", "<client-secret>");
        target.value = [
            "c8 add profile dev \\",
            "  --baseUrl=" + C8CTL_BASE_URL + " \\",
            "  --oAuthUrl=" + C8CTL_OAUTH_URL + " \\",
            "  --clientId=" + clientId + " \\",
            "  --clientSecret=" + secret + " \\",
            "",
            "c8 use profile dev"
        ].join("\n");
    }

    function generateSecret() {
        var bytes = new Uint8Array(32);
        crypto.getRandomValues(bytes);
        var binary = "";
        for (var i = 0; i < bytes.length; i++) {
            binary += String.fromCharCode(bytes[i]);
        }
        return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    }

    document.addEventListener("DOMContentLoaded", function () {
        var clientIdField = document.getElementById("clientId");
        if (clientIdField) {
            clientIdField.addEventListener("input", updateC8ctlConfig);
        }
        var regenerateButton = document.getElementById("regenerateSecretButton");
        if (regenerateButton) {
            regenerateButton.addEventListener("click", function () {
                var secret = generateSecret();
                document.getElementById("secretValue").value = secret;
                document.getElementById("secretDisplay").textContent = secret;
                updateC8ctlConfig();
            });
        }
        updateC8ctlConfig();
    });
})();
