(function() {
  const canvas = document.getElementById("netCanvas");
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  let W, H, nodes, packets, animId;
  const NODE_COUNT = 52;
  const LINK_DIST = 165;
  const PACKET_SPEED = 1.4;

  function resize() {
    W = canvas.width = canvas.offsetWidth;
    H = canvas.height = canvas.offsetHeight;
  }

  function randRange(a, b) { return a + Math.random() * (b - a); }

  function initNodes() {
    nodes = [];
    for (let i = 0; i < NODE_COUNT; i++) {
      nodes.push({
        x: randRange(0, W),
        y: randRange(0, H),
        vx: randRange(-.32, .32),
        vy: randRange(-.32, .32),
        r: randRange(1.5, 4),
        pulse: randRange(0, Math.PI * 2),
        pulseSpeed: randRange(.012, .028)
      });
    }
    packets = [];
  }

  function buildEdges() {
    const edges = [];
    for (let i = 0; i < nodes.length; i++) {
      for (let j = i + 1; j < nodes.length; j++) {
        const dx = nodes[i].x - nodes[j].x;
        const dy = nodes[i].y - nodes[j].y;
        const d = Math.sqrt(dx * dx + dy * dy);
        if (d < LINK_DIST) edges.push({ a: i, b: j, d });
      }
    }
    return edges;
  }

  function spawnPacket(edges) {
    if (edges.length === 0 || packets.length > 22) return;
    const e = edges[Math.floor(Math.random() * edges.length)];
    const rev = Math.random() < .5;
    packets.push({
      from: rev ? e.b : e.a,
      to: rev ? e.a : e.b,
      t: 0,
      speed: randRange(.004, .009)
    });
  }

  function draw() {
    ctx.clearRect(0, 0, W, H);
    const edges = buildEdges();

    edges.forEach(function(e) {
      const alpha = (1 - e.d / LINK_DIST) * .22;
      const grd = ctx.createLinearGradient(nodes[e.a].x, nodes[e.a].y, nodes[e.b].x, nodes[e.b].y);
      grd.addColorStop(0, `rgba(74,222,128,${alpha})`);
      grd.addColorStop(.5, `rgba(74,222,128,${alpha * 1.6})`);
      grd.addColorStop(1, `rgba(74,222,128,${alpha})`);
      ctx.beginPath();
      ctx.moveTo(nodes[e.a].x, nodes[e.a].y);
      ctx.lineTo(nodes[e.b].x, nodes[e.b].y);
      ctx.strokeStyle = grd;
      ctx.lineWidth = .7;
      ctx.stroke();
    });

    nodes.forEach(function(n) {
      n.pulse += n.pulseSpeed;
      const glow = .5 + .5 * Math.sin(n.pulse);
      const outerR = n.r + 3 * glow;
      const grad = ctx.createRadialGradient(n.x, n.y, 0, n.x, n.y, outerR * 2.5);
      grad.addColorStop(0, `rgba(74,222,128,${.55 + .35 * glow})`);
      grad.addColorStop(.4, `rgba(74,222,128,${.18 * glow})`);
      grad.addColorStop(1, `rgba(74,222,128,0)`);
      ctx.beginPath();
      ctx.arc(n.x, n.y, outerR * 2.5, 0, Math.PI * 2);
      ctx.fillStyle = grad;
      ctx.fill();

      ctx.beginPath();
      ctx.arc(n.x, n.y, n.r, 0, Math.PI * 2);
      ctx.fillStyle = `rgba(74,222,128,${.7 + .3 * glow})`;
      ctx.fill();

      n.x += n.vx;
      n.y += n.vy;
      if (n.x < -10) n.x = W + 10;
      if (n.x > W + 10) n.x = -10;
      if (n.y < -10) n.y = H + 10;
      if (n.y > H + 10) n.y = -10;
    });

    packets.forEach(function(p, idx) {
      p.t += p.speed;
      if (p.t >= 1) { packets.splice(idx, 1); return; }
      const na = nodes[p.from], nb = nodes[p.to];
      const px = na.x + (nb.x - na.x) * p.t;
      const py = na.y + (nb.y - na.y) * p.t;
      const pg = ctx.createRadialGradient(px, py, 0, px, py, 7);
      pg.addColorStop(0, "rgba(180,255,200,.95)");
      pg.addColorStop(.4, "rgba(74,222,128,.6)");
      pg.addColorStop(1, "rgba(74,222,128,0)");
      ctx.beginPath();
      ctx.arc(px, py, 7, 0, Math.PI * 2);
      ctx.fillStyle = pg;
      ctx.fill();
      ctx.beginPath();
      ctx.arc(px, py, 2.2, 0, Math.PI * 2);
      ctx.fillStyle = "#ffffff";
      ctx.fill();
    });

    if (Math.random() < .025) spawnPacket(edges);
    animId = requestAnimationFrame(draw);
  }

  window.addEventListener("resize", function() {
    cancelAnimationFrame(animId);
    resize();
    initNodes();
    draw();
  });

  resize();
  initNodes();
  draw();
})();


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

burgerBtn.addEventListener("click", function() {
  if (drawerOpen) closeDrawer();
  else openDrawer();
});

drawerItems.forEach(function(item) {
  item.addEventListener("click", closeDrawer);
});

document.addEventListener("click", function(e) {
  if (drawerOpen && !mobileDrawer.contains(e.target) && !burgerBtn.contains(e.target)) {
    closeDrawer();
  }
});

function getActiveSection() {
  const ids = ["home", "overview", "features", "installation", "commands", "architecture", "config"];
  let active = "home";
  ids.forEach(function(id) {
    const el = document.getElementById(id);
    if (el && el.getBoundingClientRect().top <= 90) active = id;
  });
  return active;
}

function updateNav() {
  const active = getActiveSection();
  navItems.forEach(function(item) {
    item.classList.toggle("active", item.dataset.section === active);
  });
}

window.addEventListener("scroll", function() {
  navbar.classList.toggle("scrolled", window.scrollY > 30);
  toTopBtn.classList.toggle("visible", window.scrollY > 320);
  updateNav();
  checkReveals();
}, { passive: true });

toTopBtn.addEventListener("click", function() {
  window.scrollTo({ top: 0, behavior: "smooth" });
});

document.querySelectorAll("a[href^='#']").forEach(function(link) {
  link.addEventListener("click", function(e) {
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
  tabs.forEach(function(t) {
    t.classList.toggle("active", t.dataset.tab === name);
  });
  tabPanels.forEach(function(p) {
    p.classList.toggle("active", p.id === "tab-" + name);
  });
}

tabs.forEach(function(t) {
  t.addEventListener("click", function() { activateTab(t.dataset.tab); });
});

function copyText(text, btn) {
  navigator.clipboard.writeText(text).then(function() {
    showCopied(btn);
  }).catch(function() {
    const ta = document.createElement("textarea");
    ta.value = text; ta.style.cssText = "position:fixed;opacity:0";
    document.body.appendChild(ta); ta.select();
    document.execCommand("copy"); document.body.removeChild(ta);
    showCopied(btn);
  });
}

function showCopied(btn) {
  btn.classList.add("copied");
  btn.innerHTML = '<i class="fas fa-check"></i>';
  setTimeout(function() {
    btn.classList.remove("copied");
    btn.innerHTML = '<i class="fas fa-copy"></i>';
  }, 1800);
}

copyBtns.forEach(function(btn) {
  btn.addEventListener("click", function() {
    const text = btn.dataset.copy;
    if (text) copyText(text, btn);
  });
});

function checkReveals() {
  reveals.forEach(function(el) {
    if (el.classList.contains("visible")) return;
    const rect = el.getBoundingClientRect();
    if (rect.top < window.innerHeight - 60) {
      el.classList.add("visible");
    }
  });
}

function initTypingEffect() {
  const cursor = document.querySelector(".term-cursor");
  if (!cursor) return;
}

document.addEventListener("DOMContentLoaded", function() {
  activateTab("general");
  updateNav();
  checkReveals();
  initTypingEffect();

  setTimeout(checkReveals, 100);
});
