// Page behaviours that would otherwise be inline event handlers, which the Content-Security-Policy does not
// allow (no onclick=, onsubmit=, onerror= in the templates). Loaded by every page.
(function () {
    "use strict";

    // <form data-confirm="Question?">: asks before submitting, and does not submit unless confirmed.
    document.addEventListener("submit", function (event) {
        var form = event.target;
        var question = form && form.getAttribute && form.getAttribute("data-confirm");
        if (question && !window.confirm(question)) {
            event.preventDefault();
        }
    }, true);

    // <img data-hide-on-error>: hidden when it cannot be loaded, rather than shown broken.
    function hideIfBroken(img) {
        if (img.complete && img.naturalWidth === 0) {
            img.style.display = "none";
        }
    }
    document.addEventListener("error", function (event) {
        var img = event.target;
        if (img && img.tagName === "IMG" && img.hasAttribute("data-hide-on-error")) {
            img.style.display = "none";
        }
    }, true);
    document.addEventListener("DOMContentLoaded", function () {
        document.querySelectorAll("img[data-hide-on-error]").forEach(hideIfBroken);
    });
})();
