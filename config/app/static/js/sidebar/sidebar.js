let sidebarOpen = false;
let desktopCollapsed = localStorage.getItem("tomcat-sidebar-collapsed") === "true";

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
    if (sidebarOpen) closeSidebar();
    else openSidebar();
}

function applyDesktopCollapse(collapsed) {
    const app = document.getElementById("app");
    if (collapsed) {
        app.classList.add("sidebar-collapsed");
    } else {
        app.classList.remove("sidebar-collapsed");
    }
    localStorage.setItem("tomcat-sidebar-collapsed", collapsed);
    desktopCollapsed = collapsed;
}

function toggleDesktopSidebar() {
    applyDesktopCollapse(!desktopCollapsed);
}

document.addEventListener("DOMContentLoaded", function () {
    applyDesktopCollapse(desktopCollapsed);

    const hamburger = document.getElementById("hamburger");
    if (hamburger) hamburger.addEventListener("click", toggleSidebar);

    const desktopBtn = document.getElementById("desktop-hamburger");
    if (desktopBtn) desktopBtn.addEventListener("click", toggleDesktopSidebar);

    const overlay = document.getElementById("mobile-overlay");
    if (overlay) overlay.addEventListener("click", closeSidebar);
});
