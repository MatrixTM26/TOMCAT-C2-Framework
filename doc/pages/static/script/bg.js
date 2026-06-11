(function () {
    var canvas = document.getElementById("heroBg");
    if (!canvas || typeof THREE === "undefined") return;

    var W = window.innerWidth;
    var H = window.innerHeight;

    var renderer = new THREE.WebGLRenderer({
        canvas: canvas,
        antialias: true,
        alpha: true
    });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.setSize(W, H);
    renderer.setClearColor(0x0a0a0a, 1);

    var scene = new THREE.Scene();
    var fog = new THREE.FogExp2(0x0a0a0a, 0.038);
    scene.fog = fog;

    var camera = new THREE.PerspectiveCamera(60, W / H, 0.1, 200);
    camera.position.set(0, 0, 30);

    var RED = 0xe53935;
    var RED_DIM = 0xb71c1c;
    var NODE_COUNT = 80;
    var EDGE_DIST = 14;

    var nodes = [];
    var velocities = [];

    var nodeMat = new THREE.MeshBasicMaterial({ color: RED });
    var nodeMatDim = new THREE.MeshBasicMaterial({
        color: RED_DIM,
        transparent: true,
        opacity: 0.5
    });
    var nodeGeo = new THREE.SphereGeometry(0.12, 8, 8);
    var nodeGeoSm = new THREE.SphereGeometry(0.07, 6, 6);

    var nodeGroup = new THREE.Group();
    scene.add(nodeGroup);

    for (var i = 0; i < NODE_COUNT; i++) {
        var big = Math.random() < 0.2;
        var mesh = new THREE.Mesh(
            big ? nodeGeo : nodeGeoSm,
            big ? nodeMat : nodeMatDim
        );
        var x = (Math.random() - 0.5) * 70;
        var y = (Math.random() - 0.5) * 50;
        var z = (Math.random() - 0.5) * 40;
        mesh.position.set(x, y, z);
        nodeGroup.add(mesh);
        nodes.push(mesh);
        velocities.push(
            new THREE.Vector3(
                (Math.random() - 0.5) * 0.012,
                (Math.random() - 0.5) * 0.012,
                (Math.random() - 0.5) * 0.008
            )
        );
    }

    var pulseNodes = [];
    for (var pi = 0; pi < 8; pi++) {
        var idx = Math.floor(Math.random() * NODE_COUNT);
        var ringGeo = new THREE.RingGeometry(0.25, 0.35, 16);
        var ringMat = new THREE.MeshBasicMaterial({
            color: RED,
            transparent: true,
            opacity: 0.6,
            side: THREE.DoubleSide
        });
        var ring = new THREE.Mesh(ringGeo, ringMat);
        ring.position.copy(nodes[idx].position);
        ring.userData.nodeIdx = idx;
        ring.userData.phase = Math.random() * Math.PI * 2;
        scene.add(ring);
        pulseNodes.push(ring);
    }

    var edgeGroup = new THREE.Group();
    scene.add(edgeGroup);

    function rebuildEdges() {
        while (edgeGroup.children.length) {
            edgeGroup.remove(edgeGroup.children[0]);
        }
        var edgePoints = [];
        for (var a = 0; a < NODE_COUNT; a++) {
            for (var b = a + 1; b < NODE_COUNT; b++) {
                var dist = nodes[a].position.distanceTo(nodes[b].position);
                if (dist < EDGE_DIST) {
                    edgePoints.push({
                        from: nodes[a].position.clone(),
                        to: nodes[b].position.clone(),
                        alpha: 1 - dist / EDGE_DIST
                    });
                }
            }
        }
        for (var ei = 0; ei < edgePoints.length; ei++) {
            var ep = edgePoints[ei];
            var geo = new THREE.BufferGeometry().setFromPoints([
                ep.from,
                ep.to
            ]);
            var mat = new THREE.LineBasicMaterial({
                color: RED,
                transparent: true,
                opacity: ep.alpha * 0.18
            });
            edgeGroup.add(new THREE.Line(geo, mat));
        }
    }

    rebuildEdges();

    var gridHelper = new THREE.GridHelper(120, 40, 0xe53935, 0xe53935);
    gridHelper.material.transparent = true;
    gridHelper.material.opacity = 0.03;
    gridHelper.position.y = -18;
    scene.add(gridHelper);

    var ambientParticles = [];
    var partGeo = new THREE.SphereGeometry(0.04, 4, 4);
    var partMat = new THREE.MeshBasicMaterial({
        color: RED,
        transparent: true,
        opacity: 0.3
    });
    for (var pp = 0; pp < 120; pp++) {
        var p = new THREE.Mesh(partGeo, partMat);
        p.position.set(
            (Math.random() - 0.5) * 100,
            (Math.random() - 0.5) * 70,
            (Math.random() - 0.5) * 60
        );
        p.userData.speed = (Math.random() - 0.5) * 0.005;
        scene.add(p);
        ambientParticles.push(p);
    }

    var mouse = { x: 0, y: 0 };
    var targetRot = { x: 0, y: 0 };
    var currentRot = { x: 0, y: 0 };

    document.addEventListener("mousemove", function (e) {
        mouse.x = (e.clientX / window.innerWidth - 0.5) * 2;
        mouse.y = (e.clientY / window.innerHeight - 0.5) * 2;
    });

    var edgeRebuildTimer = 0;
    var clock = new THREE.Clock();

    function animate() {
        requestAnimationFrame(animate);
        var delta = clock.getDelta();
        var elapsed = clock.getElapsedTime();

        for (var ni = 0; ni < NODE_COUNT; ni++) {
            nodes[ni].position.add(velocities[ni]);
            var pos = nodes[ni].position;
            if (pos.x > 35 || pos.x < -35) velocities[ni].x *= -1;
            if (pos.y > 25 || pos.y < -25) velocities[ni].y *= -1;
            if (pos.z > 20 || pos.z < -20) velocities[ni].z *= -1;
        }

        edgeRebuildTimer += delta;
        if (edgeRebuildTimer > 0.1) {
            rebuildEdges();
            edgeRebuildTimer = 0;
        }

        for (var ri = 0; ri < pulseNodes.length; ri++) {
            var r = pulseNodes[ri];
            r.position.copy(nodes[r.userData.nodeIdx].position);
            r.lookAt(camera.position);
            var phase = r.userData.phase + elapsed * 1.5;
            var scale = 0.8 + Math.sin(phase) * 0.5 + 0.5;
            r.scale.setScalar(scale);
            r.material.opacity = (1 - (scale - 0.8) / 1) * 0.5;
        }

        for (var api = 0; api < ambientParticles.length; api++) {
            ambientParticles[api].position.y +=
                ambientParticles[api].userData.speed;
            if (ambientParticles[api].position.y > 35)
                ambientParticles[api].position.y = -35;
            if (ambientParticles[api].position.y < -35)
                ambientParticles[api].position.y = 35;
        }

        targetRot.y = mouse.x * 0.08;
        targetRot.x = mouse.y * -0.05;
        currentRot.x += (targetRot.x - currentRot.x) * 0.04;
        currentRot.y += (targetRot.y - currentRot.y) * 0.04;

        scene.rotation.x = currentRot.x;
        scene.rotation.y = currentRot.y + elapsed * 0.018;

        renderer.render(scene, camera);
    }

    animate();

    window.addEventListener("resize", function () {
        W = window.innerWidth;
        H = window.innerHeight;
        camera.aspect = W / H;
        camera.updateProjectionMatrix();
        renderer.setSize(W, H);
    });
})();
