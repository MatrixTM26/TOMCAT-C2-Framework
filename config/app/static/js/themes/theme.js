let currentTheme = localStorage.getItem("tomcat-theme") || "dark";
const html = document.documentElement;

function applyTheme(theme) {
    html.setAttribute("data-theme", theme);
    currentTheme = theme;
    localStorage.setItem("tomcat-theme", theme);
    let icon = theme === "dark" ? "fa-moon" : "fa-sun";
    ["theme-icon", "sidebar-theme-icon", "mobile-theme-icon"].forEach(
        function (id) {
            let el = document.getElementById(id);
            if (el) el.className = "fas " + icon;
        }
    );
}

function toggleTheme() {
    applyTheme(currentTheme === "dark" ? "light" : "dark");
}

applyTheme(currentTheme);

document.addEventListener("DOMContentLoaded", function () {
    ["theme-toggle-btn", "sidebar-theme-btn", "mobile-theme-btn"].forEach(
        function (id) {
            let btn = document.getElementById(id);
            if (btn) btn.addEventListener("click", toggleTheme);
        }
    );
});
