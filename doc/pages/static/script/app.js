const navbar = document.getElementById("navbar");
const burgerBtn = document.getElementById("burgerBtn");
const burgerIcon = document.getElementById("burgerIcon");
const mobileDrawer = document.getElementById("mobileDrawer");
const toTopBtn = document.getElementById("toTop");
const navItems = document.querySelectorAll(".nav-item");
const drawerItems = document.querySelectorAll(".drawer-item");
const tabs = document.querySelectorAll(".tab");
const tabPanels = document.querySelectorAll(".tab-panel");
const copyBtns = document.querySelectorAll(".cb-copy");
const reveals = document.querySelectorAll(".reveal");

let drawerOpen = false;

function openDrawer() {
    drawerOpen = true;
    mobileDrawer.classList.add("open");
    burgerIcon.className = "fas fa-xmark";
    document.body.style.overflow = "hidden";
}

function closeDrawer() {
    drawerOpen = false;
    mobileDrawer.classList.remove("open");
    burgerIcon.className = "fas fa-bars";
    document.body.style.overflow = "";
}

burgerBtn.addEventListener("click", function () {
    if (drawerOpen) closeDrawer();
    else openDrawer();
});

drawerItems.forEach(function (item) {
    item.addEventListener("click", closeDrawer);
});

document.addEventListener("click", function (e) {
    if (
        drawerOpen &&
        !mobileDrawer.contains(e.target) &&
        !burgerBtn.contains(e.target)
    ) {
        closeDrawer();
    }
});

function getActiveSection() {
    const ids = [
        "home",
        "overview",
        "features",
        "installation",
        "commands",
        "architecture",
        "config"
    ];
    let active = "home";
    ids.forEach(function (id) {
        const el = document.getElementById(id);
        if (el && el.getBoundingClientRect().top <= 90) active = id;
    });
    return active;
}

function updateNav() {
    const active = getActiveSection();
    navItems.forEach(function (item) {
        item.classList.toggle("active", item.dataset.section === active);
    });
}

window.addEventListener(
    "scroll",
    function () {
        navbar.classList.toggle("scrolled", window.scrollY > 30);
        toTopBtn.classList.toggle("visible", window.scrollY > 320);
        updateNav();
        checkReveals();
    },
    { passive: true }
);

toTopBtn.addEventListener("click", function () {
    window.scrollTo({ top: 0, behavior: "smooth" });
});

document.querySelectorAll("a[href^='#']").forEach(function (link) {
    link.addEventListener("click", function (e) {
        const id = link.getAttribute("href").slice(1);
        const target = document.getElementById(id);
        if (!target) return;
        e.preventDefault();
        const offset = target.getBoundingClientRect().top + window.scrollY - 72;
        window.scrollTo({ top: offset, behavior: "smooth" });
        closeDrawer();
    });
});

function activateTab(name) {
    tabs.forEach(function (t) {
        t.classList.toggle("active", t.dataset.tab === name);
    });
    tabPanels.forEach(function (p) {
        p.classList.toggle("active", p.id === "tab-" + name);
    });
}

tabs.forEach(function (t) {
    t.addEventListener("click", function () {
        activateTab(t.dataset.tab);
    });
});

function copyText(text, btn) {
    navigator.clipboard
        .writeText(text)
        .then(function () {
            showCopied(btn);
        })
        .catch(function () {
            const ta = document.createElement("textarea");
            ta.value = text;
            ta.style.cssText = "position:fixed;opacity:0";
            document.body.appendChild(ta);
            ta.select();
            document.execCommand("copy");
            document.body.removeChild(ta);
            showCopied(btn);
        });
}

function showCopied(btn) {
    btn.classList.add("copied");
    btn.innerHTML = '<i class="fas fa-check"></i>';
    setTimeout(function () {
        btn.classList.remove("copied");
        btn.innerHTML = '<i class="fas fa-copy"></i>';
    }, 1800);
}

copyBtns.forEach(function (btn) {
    btn.addEventListener("click", function () {
        const text = btn.dataset.copy;
        if (text) copyText(text, btn);
    });
});

function checkReveals() {
    reveals.forEach(function (el) {
        if (el.classList.contains("visible")) return;
        const rect = el.getBoundingClientRect();
        if (rect.top < window.innerHeight - 60) {
            el.classList.add("visible");
        }
    });
}

(function initScreenshotSlider() {
    const track = document.getElementById("screenshotTrack");
    const prevBtn = document.getElementById("ssPrev");
    const nextBtn = document.getElementById("ssNext");
    const dots = document.querySelectorAll(".screenshot-dot");
    const titleEl = document.getElementById("screenshotTitle");

    if (!track || !prevBtn || !nextBtn) return;

    const titles = [
        "tomcat-c2 — operator console",
        "tomcat-c2 — web panel",
        "tomcat-c2 — session view",
        "tomcat-c2 — agent manager"
    ];

    const total = dots.length;
    let current = 0;

    function goTo(idx) {
        current = (idx + total) % total;
        track.style.transform = "translateX(-" + current * 100 + "%)";
        dots.forEach(function (d, i) {
            d.classList.toggle("active", i === current);
        });
        if (titleEl && titles[current]) titleEl.textContent = titles[current];
    }

    prevBtn.addEventListener("click", function () {
        goTo(current - 1);
    });
    nextBtn.addEventListener("click", function () {
        goTo(current + 1);
    });

    dots.forEach(function (dot) {
        dot.addEventListener("click", function () {
            goTo(parseInt(dot.dataset.idx, 10));
        });
    });

    var autoTimer = setInterval(function () {
        goTo(current + 1);
    }, 4000);

    var sliderEl = document.getElementById("heroScreenshot");
    if (sliderEl) {
        sliderEl.addEventListener("mouseenter", function () {
            clearInterval(autoTimer);
        });
        sliderEl.addEventListener("mouseleave", function () {
            clearInterval(autoTimer);
            autoTimer = setInterval(function () {
                goTo(current + 1);
            }, 4000);
        });
    }
})();

document.addEventListener("DOMContentLoaded", function () {
    activateTab("cli");
    updateNav();
    checkReveals();
    setTimeout(checkReveals, 100);
});
