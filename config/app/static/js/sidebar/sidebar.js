let sidebarOpen = false;

function openSidebar() {
    sidebarOpen = true;
    document.getElementById("sidebar").classList.add("open");
    document.getElementById("mobile-overlay").classList.add("active");
}

function closeSidebar() {
    sidebarOpen = false;
    document.getElementById("sidebar").classList.remove("open");
    document.getElementById("mobile-overlay").classList.remove("active");
}

function toggleSidebar() {
    if (sidebarOpen) {
        closeSidebar();
    } else {
        openSidebar();
    }
}

document.addEventListener("DOMContentLoaded", function () {
    let hamburger = document.getElementById("hamburger");
    if (hamburger) hamburger.addEventListener("click", toggleSidebar);
    let overlay = document.getElementById("mobile-overlay");
    if (overlay) overlay.addEventListener("click", closeSidebar);
});
