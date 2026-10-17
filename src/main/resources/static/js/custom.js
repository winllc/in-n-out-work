function showToast(message, options = {}) {
    const {
        type = "primary",   // primary | success | danger | warning | info
        delay = 3000,       // ms
        title = "Notice"
    } = options;

    const container = document.getElementById("toastContainer");

    const toastEl = document.createElement("div");
    toastEl.className = `toast align-items-center text-bg-${type} border-0 show`;
    toastEl.setAttribute("role", "alert");
    toastEl.setAttribute("aria-live", "assertive");
    toastEl.setAttribute("aria-atomic", "true");

    // Built as text, not HTML: messages can carry what the server echoed back of a request.
    const row = document.createElement("div");
    row.className = "d-flex";
    const body = document.createElement("div");
    body.className = "toast-body";
    const heading = document.createElement("strong");
    heading.className = "me-2";
    heading.textContent = title;
    body.append(heading, document.createTextNode(String(message)));
    const close = document.createElement("button");
    close.type = "button";
    close.className = "btn-close btn-close-white me-2 m-auto";
    row.append(body, close);
    toastEl.appendChild(row);

    container.appendChild(toastEl);

    // close button
    toastEl.querySelector(".btn-close").onclick = () => removeToast(toastEl);

    // auto remove
    setTimeout(() => removeToast(toastEl), delay);
}

function removeToast(toastEl) {
    toastEl.classList.remove("show");
    toastEl.classList.add("hide");
    setTimeout(() => toastEl.remove(), 300);
}