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

    toastEl.innerHTML = `
    <div class="d-flex">
      <div class="toast-body">
        <strong class="me-2">${title}</strong>
        ${message}
      </div>
      <button type="button" class="btn-close btn-close-white me-2 m-auto"></button>
    </div>
  `;

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