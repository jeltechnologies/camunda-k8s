// Builds the "c8 add profile dev" snippet shown on the add/edit API client pages, from
// C8CTL_BASE_URL/C8CTL_OAUTH_URL (inlined by the page via Thymeleaf) plus the current #clientId
// and #secretValue field values. Re-run on load and on every #clientId input so typing a client
// ID (add-client.html) updates the snippet live; edit-client.html's #clientId is read-only, so
// there the snippet is effectively just rendered once from the server-supplied values.
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

    document.addEventListener("DOMContentLoaded", function () {
        var clientIdField = document.getElementById("clientId");
        if (clientIdField) {
            clientIdField.addEventListener("input", updateC8ctlConfig);
        }
        updateC8ctlConfig();
    });
})();
