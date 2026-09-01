// Builds the "c8 add profile dev" snippet shown on the add/edit API client pages, from
// C8CTL_BASE_URL/C8CTL_OAUTH_URL (inlined by the page via Thymeleaf) plus the current #clientId
// and #secretValue field values. Re-run on load and on every #clientId / #secretValue input so
// typing a client ID (add-client.html) or editing the secret on either page updates the snippet
// live; edit-client.html's #clientId is read-only, so there only the secret half moves.
//
// Also wires the "Generate new" button (#regenerateSecretButton) on both pages: it fills #secretValue
// with a fresh random secret in the browser, in the same base64url, 32-byte shape as
// AdminClientController.generateSecret(). On the edit page the new value is only persisted when the
// form's "OK" is clicked (the controller's edit handler now takes the secret field), so generating
// and saving are two explicit steps.
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
        var secretField = document.getElementById("secretValue");
        if (secretField) {
            secretField.addEventListener("input", updateC8ctlConfig);
        }
        var regenerateButton = document.getElementById("regenerateSecretButton");
        if (regenerateButton) {
            regenerateButton.addEventListener("click", function () {
                document.getElementById("secretValue").value = generateSecret();
                updateC8ctlConfig();
            });
        }
        updateC8ctlConfig();
    });
})();
