import * as THREE from "three";
import { RoomEnvironment } from "three/addons/environments/RoomEnvironment.js";
import { EffectComposer } from "three/addons/postprocessing/EffectComposer.js";
import { RenderPass } from "three/addons/postprocessing/RenderPass.js";
import { ShaderPass } from "three/addons/postprocessing/ShaderPass.js";
import { UnrealBloomPass } from "three/addons/postprocessing/UnrealBloomPass.js";
import { FXAAShader } from "three/addons/shaders/FXAAShader.js";
import { SVGLoader } from "three/addons/loaders/SVGLoader.js";
import { landingConfig, type QualityTier } from "./landing-config";
import { buildBeatIntervals, damp, linger, sampleBeat } from "./scroll-math";
import { downgradeQuality, selectQuality, type QualityProfile } from "./quality";

const TEAL = 0x0f766e;
const SIGNAL = 0x14b8a6;
const MINT = 0x5eead4;
const CREAM = 0xf4f7f2;
const INK = 0x0b1f1d;
const CORAL = 0xff8a65;
const SCENE_Z = [0, -17, -35, -52, -69, -86, -103];
const INTERACTIVE_LAYER = 2;

type SelectHandler = (value: { label: string; detail: string } | null) => void;

interface WorldOptions {
  onSelect: SelectHandler;
  onFallback: (reason: string) => void;
  onQuality: (tier: QualityTier) => void;
}

interface BeatWorld {
  root: THREE.Group;
  stage: THREE.Group;
  mixer: THREE.AnimationMixer;
  action: THREE.AnimationAction;
  materials: THREE.Material[];
}

const waveformVertex = /* glsl */ `
  uniform float uTime;
  uniform float uProgress;
  varying float vPulse;
  void main() {
    vec3 p = position;
    float envelope = sin(uv.x * 3.14159265);
    float wave = sin(uv.x * 38.0 - uTime * 3.2 + uProgress * 10.0);
    p.y += wave * envelope * (0.18 + uProgress * 0.38);
    vPulse = 0.5 + 0.5 * wave;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(p, 1.0);
  }
`;

const waveformFragment = /* glsl */ `
  uniform vec3 uColor;
  uniform float uOpacity;
  varying float vPulse;
  void main() {
    gl_FragColor = vec4(uColor * (0.72 + vPulse * 0.55), uOpacity);
  }
`;

const pulseVertex = /* glsl */ `
  varying vec3 vNormal;
  varying vec3 vWorld;
  void main() {
    vNormal = normalize(normalMatrix * normal);
    vec4 world = modelMatrix * vec4(position, 1.0);
    vWorld = world.xyz;
    gl_Position = projectionMatrix * viewMatrix * world;
  }
`;

const pulseFragment = /* glsl */ `
  uniform vec3 uColor;
  uniform float uPulse;
  uniform float uOpacity;
  varying vec3 vNormal;
  varying vec3 vWorld;
  void main() {
    vec3 eye = normalize(cameraPosition - vWorld);
    float fresnel = pow(1.0 - max(dot(eye, vNormal), 0.0), 2.2);
    float alpha = (0.12 + fresnel * 0.82) * uOpacity;
    gl_FragColor = vec4(uColor * (0.75 + uPulse * 0.65), alpha);
  }
`;

function makeRoundedShape(width: number, height: number, radius: number) {
  const x = -width / 2;
  const y = -height / 2;
  const shape = new THREE.Shape();
  shape.moveTo(x + radius, y);
  shape.lineTo(x + width - radius, y);
  shape.quadraticCurveTo(x + width, y, x + width, y + radius);
  shape.lineTo(x + width, y + height - radius);
  shape.quadraticCurveTo(x + width, y + height, x + width - radius, y + height);
  shape.lineTo(x + radius, y + height);
  shape.quadraticCurveTo(x, y + height, x, y + height - radius);
  shape.lineTo(x, y + radius);
  shape.quadraticCurveTo(x, y, x + radius, y);
  return shape;
}

function setOpacity(material: THREE.Material, opacity: number) {
  if (!("opacity" in material)) return;
  const m = material as THREE.Material & { opacity: number };
  m.transparent = opacity < 0.999;
  m.opacity = opacity;
  m.depthWrite = opacity > 0.5;
}

export class PinnaWorld {
  private canvas: HTMLCanvasElement;
  private renderer: THREE.WebGLRenderer;
  private scene = new THREE.Scene();
  private camera = new THREE.PerspectiveCamera(37, 1, 0.1, 220);
  private composer: EffectComposer | null = null;
  private fxaa: ShaderPass | null = null;
  private clock = new THREE.Clock();
  private targetProgress = 0;
  private progress = 0;
  private raf = 0;
  private disposed = false;
  private visible = true;
  private contextRestoreAttempts = 0;
  private quality: QualityProfile;
  private intervals = buildBeatIntervals(landingConfig.beats);
  private beatWorlds: BeatWorld[] = [];
  private interactive: THREE.Object3D[] = [];
  private mixers: THREE.AnimationMixer[] = [];
  private shaderMaterials: THREE.ShaderMaterial[] = [];
  private raycaster = new THREE.Raycaster();
  private pointer = new THREE.Vector2(3, 3);
  private pointerTarget = new THREE.Vector2();
  private pointerCurrent = new THREE.Vector2();
  private packetMesh: THREE.InstancedMesh | null = null;
  private routeCurve!: THREE.CatmullRomCurve3;
  private cameraCurve!: THREE.CatmullRomCurve3;
  private targetCurve!: THREE.CatmullRomCurve3;
  private activeRim = new THREE.PointLight(MINT, 2.6, 18, 2);
  private onSelect: SelectHandler;
  private onFallback: (reason: string) => void;
  private onQuality: (tier: QualityTier) => void;
  private width = 0;
  private height = 0;
  private slowMs = 0;
  private lastFrame = performance.now();
  private cleanup: Array<() => void> = [];
  private pmrem: THREE.PMREMGenerator | null = null;

  constructor(canvas: HTMLCanvasElement, options: WorldOptions) {
    this.canvas = canvas;
    this.onSelect = options.onSelect;
    this.onFallback = options.onFallback;
    this.onQuality = options.onQuality;
    this.quality = selectQuality({
      width: window.innerWidth,
      coarse: matchMedia("(pointer: coarse)").matches,
      reducedMotion: matchMedia("(prefers-reduced-motion: reduce)").matches,
      memory: (navigator as Navigator & { deviceMemory?: number }).deviceMemory,
      cores: navigator.hardwareConcurrency,
    });

    try {
      this.renderer = new THREE.WebGLRenderer({ canvas, antialias: this.quality.post === "none", alpha: true, powerPreference: "high-performance" });
    } catch {
      options.onFallback("WebGL is unavailable on this device.");
      throw new Error("WebGL unavailable");
    }

    this.quality = selectQuality({
      width: window.innerWidth,
      coarse: matchMedia("(pointer: coarse)").matches,
      reducedMotion: false,
      memory: (navigator as Navigator & { deviceMemory?: number }).deviceMemory,
      cores: navigator.hardwareConcurrency,
      maxTextureSize: this.renderer.capabilities.maxTextureSize,
    });
    this.onQuality(this.quality.tier);
    this.setupRenderer();
    this.buildWorld();
    this.bindEvents();
    this.resize(true);
    this.render();
  }

  setTargetProgress(progress: number) {
    this.targetProgress = THREE.MathUtils.clamp(progress, 0, 1);
  }

  private setupRenderer() {
    this.renderer.setClearColor(INK, 0);
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 0.82;
    this.renderer.shadowMap.enabled = this.quality.shadows;
    this.renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    this.renderer.setPixelRatio(Math.min(devicePixelRatio, this.quality.dpr));
    this.pmrem = new THREE.PMREMGenerator(this.renderer);
    this.scene.environment = this.pmrem.fromScene(new RoomEnvironment(), 0.03).texture;
    this.scene.fog = new THREE.FogExp2(INK, 0.018);
  }

  private buildWorld() {
    const hemi = new THREE.HemisphereLight(CREAM, TEAL, 1.25);
    this.scene.add(hemi);
    const key = new THREE.DirectionalLight(0xffeadb, 2.25);
    key.position.set(8, 13, 8);
    key.castShadow = this.quality.shadows;
    key.shadow.mapSize.set(this.quality.tier === "high" ? 1024 : 512, this.quality.tier === "high" ? 1024 : 512);
    key.shadow.camera.left = -11;
    key.shadow.camera.right = 11;
    key.shadow.camera.top = 11;
    key.shadow.camera.bottom = -11;
    this.scene.add(key, this.activeRim);

    this.routeCurve = new THREE.CatmullRomCurve3(SCENE_Z.map((z, i) => new THREE.Vector3(i % 2 ? -0.8 : 0.8, 0.2, z)), false, "catmullrom", 0.35);
    this.cameraCurve = new THREE.CatmullRomCurve3(landingConfig.beats.map((b) => new THREE.Vector3(...b.camera.position)), false, "catmullrom", 0.28);
    this.targetCurve = new THREE.CatmullRomCurve3(landingConfig.beats.map((b) => new THREE.Vector3(...b.camera.target)), false, "catmullrom", 0.28);

    const ribbonMat = new THREE.MeshStandardMaterial({ color: SIGNAL, emissive: TEAL, emissiveIntensity: 0.75, roughness: 0.38, metalness: 0 });
    const ribbon = new THREE.Mesh(new THREE.TubeGeometry(this.routeCurve, 220, 0.055, 8, false), ribbonMat);
    ribbon.receiveShadow = false;
    this.scene.add(ribbon);

    landingConfig.beats.forEach((beat, index) => {
      const root = new THREE.Group();
      root.position.z = SCENE_Z[index];
      root.name = beat.id;
      const stage = new THREE.Group();
      root.add(stage);
      this.scene.add(root);
      const materials: THREE.Material[] = [];
      this.buildBeat(root, stage, index, materials);

      const mixer = new THREE.AnimationMixer(stage);
      const clip = new THREE.AnimationClip(`beat-${beat.id}`, 1, [
        new THREE.NumberKeyframeTrack(".position[y]", [0, 0.5, 1], [-0.45, 0.1, 0]),
        new THREE.NumberKeyframeTrack(".scale[x]", [0, 0.5, 1], [0.82, 1.03, 1]),
        new THREE.NumberKeyframeTrack(".scale[y]", [0, 0.5, 1], [0.82, 1.03, 1]),
        new THREE.NumberKeyframeTrack(".scale[z]", [0, 0.5, 1], [0.82, 1.03, 1]),
      ]);
      const action = mixer.clipAction(clip);
      action.play();
      action.paused = true;
      this.mixers.push(mixer);
      this.beatWorlds.push({ root, stage, mixer, action, materials });
    });

    this.buildPackets();
    this.setupPostProcessing();
  }

  private clay(color: number, materials: THREE.Material[], emissive = 0) {
    const material = new THREE.MeshStandardMaterial({ color, roughness: 0.72, metalness: 0.02, emissive: emissive || 0, emissiveIntensity: emissive ? 0.28 : 0 });
    materials.push(material);
    return material;
  }

  private buildBeat(root: THREE.Group, stage: THREE.Group, index: number, materials: THREE.Material[]) {
    const island = new THREE.Mesh(
      new THREE.CylinderGeometry(4.6 - index * 0.08, 5.1 - index * 0.08, 0.62, this.quality.segments * 2, 1),
      this.clay(index % 2 ? 0x135e59 : TEAL, materials),
    );
    island.position.y = -0.8;
    island.receiveShadow = true;
    stage.add(island);
    const rim = new THREE.Mesh(new THREE.TorusGeometry(4.15, 0.04, 5, 80), this.clay(MINT, materials, MINT));
    rim.rotation.x = Math.PI / 2;
    rim.position.y = -0.45;
    stage.add(rim);

    if (index === 0) this.buildTogether(stage, materials);
    if (index === 1) this.buildChoose(stage, materials);
    if (index === 2) this.buildCreate(stage, materials);
    if (index === 3) this.buildJoin(stage, materials);
    if (index === 4) this.buildSync(stage, materials);
    if (index === 5) this.buildLocal(stage, materials);
    if (index === 6) this.buildFinale(stage, materials);
  }

  private buildTogether(stage: THREE.Group, materials: THREE.Material[]) {
    stage.add(this.makePinnaMark(0.075, 1.45, materials));
    [[-2.4, 0.1, 0], [2.4, 0.25, 0.3], [0, 0.15, 2.35], [0.2, 0.35, -2.2]].forEach((pos, i) => {
      const phone = this.makePhone(i === 0 ? CORAL : MINT, `listener-${i + 1}`, i === 0 ? "Host phone" : `Listener ${i + 1}`);
      phone.position.set(pos[0], pos[1], pos[2]);
      phone.rotation.set(-0.12, i * 1.4, i % 2 ? -0.12 : 0.12);
      stage.add(phone);
    });
    for (let i = 0; i < 3; i++) {
      const ring = this.makePulseRing(materials);
      ring.scale.setScalar(1.2 + i * 0.8);
      ring.position.y = 0.2;
      stage.add(ring);
    }
  }

  private buildChoose(stage: THREE.Group, materials: THREE.Material[]) {
    const host = this.makePhone(MINT, "host-2", "Host · local files and audio links");
    host.position.set(1.25, 0.4, 0);
    host.rotation.z = -0.12;
    stage.add(host);
    for (let i = 0; i < 6; i++) {
      const disc = new THREE.Mesh(new THREE.CylinderGeometry(0.68, 0.68, 0.1, 32), this.clay(i % 2 ? CORAL : CREAM, materials));
      disc.rotation.x = Math.PI / 2;
      disc.position.set(-3 + i * 0.7, 0.2 + Math.sin(i) * 0.45, 0.2 + Math.cos(i) * 0.6);
      stage.add(disc);
    }
    const card = new THREE.Mesh(new THREE.BoxGeometry(2.4, 1.25, 0.1), this.clay(CREAM, materials));
    card.position.set(-1.4, 1.25, -0.7);
    card.rotation.y = 0.25;
    stage.add(card);
  }

  private buildCreate(stage: THREE.Group, materials: THREE.Material[]) {
    const host = this.makePhone(CORAL, "host-3", "Host · room open");
    host.position.y = 0.7;
    stage.add(host);
    for (let i = 0; i < 3; i++) {
      const arc = new THREE.Mesh(new THREE.TorusGeometry(1.2 + i * 0.72, 0.075, 7, 64, Math.PI), this.clay(MINT, materials, MINT));
      arc.rotation.x = Math.PI / 2;
      arc.rotation.z = Math.PI;
      arc.position.set(0, 0.2 + i * 0.42, 0.2);
      stage.add(arc);
    }
    const dome = this.makePulseSphere(materials, 3.45, 0.17);
    dome.scale.y = 0.52;
    dome.position.y = -0.25;
    stage.add(dome);
  }

  private buildJoin(stage: THREE.Group, materials: THREE.Material[]) {
    const count = 12 * 12;
    const tileGeometry = new THREE.BoxGeometry(0.18, 0.18, 0.12);
    const tileMaterial = this.clay(CREAM, materials, CREAM);
    const tiles = new THREE.InstancedMesh(tileGeometry, tileMaterial, count);
    const matrix = new THREE.Matrix4();
    for (let y = 0; y < 12; y++) {
      for (let x = 0; x < 12; x++) {
        const seed = (x * 17 + y * 31 + x * y) % 7;
        const visible = seed < 4 || x < 2 || y < 2 || x > 9 || y > 9;
        matrix.makeScale(visible ? 1 : 0.08, visible ? 1 : 0.08, 1);
        matrix.setPosition((x - 5.5) * 0.25, (y - 5.5) * 0.25 + 1.15, 0);
        tiles.setMatrixAt(y * 12 + x, matrix);
      }
    }
    stage.add(tiles);
    const host = this.makePhone(CORAL, "host-4", "Host · scan this room code");
    host.position.set(-2.6, 0.2, 0.5);
    const guestA = this.makePhone(MINT, "listener-4a", "Listener · joining");
    guestA.position.set(2.4, 0.2, 0.5);
    stage.add(host, guestA);
  }

  private buildSync(stage: THREE.Group, materials: THREE.Material[]) {
    const phonePositions = [[-2.7, 0, 0], [-0.9, 0.1, -0.2], [0.9, 0.1, 0.15], [2.7, 0, -0.1]];
    phonePositions.forEach((pos, i) => {
      const phone = this.makePhone(i === 0 ? CORAL : MINT, i ? `listener-5${String.fromCharCode(96 + i)}` : "host-5", i ? `Listener ${i} · synced` : "Host · reference clock");
      phone.position.set(pos[0], pos[1], pos[2]);
      phone.rotation.z = (i - 1.5) * 0.05;
      stage.add(phone);
    });
    const waveMaterial = new THREE.ShaderMaterial({
      uniforms: { uTime: { value: 0 }, uProgress: { value: 0 }, uColor: { value: new THREE.Color(MINT) }, uOpacity: { value: 1 } },
      vertexShader: waveformVertex,
      fragmentShader: waveformFragment,
      transparent: true,
      side: THREE.DoubleSide,
      blending: THREE.AdditiveBlending,
    });
    this.shaderMaterials.push(waveMaterial);
    materials.push(waveMaterial);
    const wave = new THREE.Mesh(new THREE.PlaneGeometry(6.8, 1.25, 96, 1), waveMaterial);
    wave.position.set(0, 2.1, 0.1);
    stage.add(wave);
  }

  private buildLocal(stage: THREE.Group, materials: THREE.Material[]) {
    [[-2.3, 0, 0], [2.2, 0.1, 0.3], [0.2, 0.2, -2.1]].forEach((pos, i) => {
      const phone = this.makePhone(i ? MINT : CORAL, `local-phone-${i}`, i ? "Listener · inside local room" : "Host · local only");
      phone.position.set(pos[0], pos[1], pos[2]);
      stage.add(phone);
    });
    const boundary = this.makePulseSphere(materials, 4.15, 0.28);
    boundary.scale.y = 0.65;
    boundary.position.y = 0.35;
    boundary.userData = { label: "Local room", detail: "No cloud relay. No analytics." };
    boundary.layers.enable(INTERACTIVE_LAYER);
    this.interactive.push(boundary);
    stage.add(boundary);
    for (let i = 0; i < 3; i++) {
      const cloud = new THREE.Mesh(new THREE.SphereGeometry(0.45 + i * 0.08, 12, 8), this.clay(0x41615d, materials));
      cloud.position.set(-5.6 + i * 5.5, 2.8 + i * 0.25, -1.2);
      stage.add(cloud);
    }
  }

  private buildFinale(stage: THREE.Group, materials: THREE.Material[]) {
    const mark = this.makePinnaMark(0.12, 2.5, materials);
    mark.userData = { label: "Pinna", detail: "One room. One beat." };
    mark.layers.enable(INTERACTIVE_LAYER);
    this.interactive.push(mark);
    stage.add(mark);
    for (let i = 0; i < 7; i++) {
      const phone = this.makePhone(i === 0 ? CORAL : MINT, `final-phone-${i}`, i ? "Connected listener" : "Room host");
      const angle = (i / 7) * Math.PI * 2;
      phone.scale.setScalar(0.58);
      phone.position.set(Math.cos(angle) * 3.25, 0.25 + Math.sin(i * 2) * 0.35, Math.sin(angle) * 1.7);
      phone.rotation.y = -angle + Math.PI / 2;
      stage.add(phone);
    }
  }

  private makePhone(accent: number, id: string, detail: string) {
    const group = new THREE.Group();
    const shellMaterial = new THREE.MeshStandardMaterial({ color: INK, roughness: 0.52, metalness: 0.08 });
    const glassMaterial = new THREE.MeshPhysicalMaterial({ color: 0x143d39, roughness: 0.2, metalness: 0.05, clearcoat: 0.9, clearcoatRoughness: 0.18, transmission: this.quality.tier === "high" ? 0.12 : 0 });
    const shell = new THREE.Mesh(new THREE.ExtrudeGeometry(makeRoundedShape(1.05, 2.02, 0.22), { depth: 0.17, bevelEnabled: true, bevelSize: 0.07, bevelThickness: 0.06, bevelSegments: 3 }), shellMaterial);
    shell.rotation.x = -Math.PI / 2;
    shell.position.set(0, -0.05, 0.53);
    shell.castShadow = this.quality.shadows;
    const screen = new THREE.Mesh(new THREE.PlaneGeometry(0.86, 1.66), glassMaterial);
    screen.rotation.x = -Math.PI / 2;
    screen.position.y = 0.19;
    const signal = new THREE.Mesh(new THREE.CircleGeometry(0.17, 20), new THREE.MeshBasicMaterial({ color: accent, transparent: true, opacity: 0.95 }));
    signal.rotation.x = -Math.PI / 2;
    signal.position.set(0, 0.2, 0.05);
    group.add(shell, screen, signal);
    group.userData = { id, label: id.includes("host") || id === "listener-1" ? "Host" : "Listener", detail };
    group.traverse((object) => object.layers.enable(INTERACTIVE_LAYER));
    this.interactive.push(group);
    return group;
  }

  private makePinnaMark(depth: number, scale: number, materials: THREE.Material[]) {
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 160"><path fill="#fff" d="M39 64C34 39 52 18 79 18c27 0 46 20 46 47 0 18-9 27-15 39-5 10-4 21-12 31-10 13-29 14-41 4-8-7-11-16-11-28v-8h18v8c0 7 2 12 7 15 5 4 12 3 16-2 5-6 4-16 10-28 5-10 10-16 10-31 0-17-11-29-28-29-17 0-27 12-23 28H39Z"/><path fill="#fff" fill-rule="evenodd" d="M47 40h29c18 0 32 13 32 31s-14 32-32 32H65v12H47V40Zm18 17v29h11c8 0 14-6 14-15 0-8-6-14-14-14H65Z"/></svg>`;
    const data = new SVGLoader().parse(svg);
    const shapes = data.paths.flatMap((path) => SVGLoader.createShapes(path));
    const group = new THREE.Group();
    shapes.forEach((shape) => {
      const geometry = new THREE.ExtrudeGeometry(shape, { depth, bevelEnabled: true, bevelSize: 0.8, bevelThickness: 0.8, bevelSegments: 3 });
      geometry.center();
      const mesh = new THREE.Mesh(geometry, new THREE.MeshPhysicalMaterial({ color: CREAM, roughness: 0.4, clearcoat: 0.32, emissive: TEAL, emissiveIntensity: 0.035 }));
      mesh.scale.setScalar(scale / 40);
      mesh.rotation.x = -0.1;
      mesh.castShadow = this.quality.shadows;
      group.add(mesh);
    });
    materials.push(...group.children.map((child) => (child as THREE.Mesh).material as THREE.Material));
    return group;
  }

  private makePulseRing(materials: THREE.Material[]) {
    const material = new THREE.ShaderMaterial({
      uniforms: { uColor: { value: new THREE.Color(MINT) }, uPulse: { value: 0 }, uOpacity: { value: 0.58 } },
      vertexShader: pulseVertex,
      fragmentShader: pulseFragment,
      transparent: true,
      depthWrite: false,
      side: THREE.DoubleSide,
      blending: THREE.AdditiveBlending,
    });
    this.shaderMaterials.push(material);
    materials.push(material);
    const ring = new THREE.Mesh(new THREE.TorusGeometry(1, 0.035, 7, 80), material);
    ring.rotation.x = Math.PI / 2;
    return ring;
  }

  private makePulseSphere(materials: THREE.Material[], radius: number, opacity: number) {
    const material = new THREE.ShaderMaterial({
      uniforms: { uColor: { value: new THREE.Color(MINT) }, uPulse: { value: 0 }, uOpacity: { value: opacity } },
      vertexShader: pulseVertex,
      fragmentShader: pulseFragment,
      transparent: true,
      depthWrite: false,
      side: THREE.DoubleSide,
      blending: THREE.AdditiveBlending,
    });
    this.shaderMaterials.push(material);
    materials.push(material);
    return new THREE.Mesh(new THREE.SphereGeometry(radius, this.quality.segments * 2, this.quality.segments), material);
  }

  private buildPackets() {
    const geometry = new THREE.IcosahedronGeometry(0.07, 0);
    const material = new THREE.MeshBasicMaterial({ color: MINT, transparent: true, opacity: 0.88 });
    this.packetMesh = new THREE.InstancedMesh(geometry, material, this.quality.particles);
    this.packetMesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
    this.scene.add(this.packetMesh);
  }

  private setupPostProcessing() {
    if (this.quality.post === "none") return;
    this.composer = new EffectComposer(this.renderer);
    this.composer.addPass(new RenderPass(this.scene, this.camera));
    const bloom = new UnrealBloomPass(new THREE.Vector2(1, 1), this.quality.post === "full" ? 0.1 : 0.06, 0.16, 0.98);
    this.composer.addPass(bloom);
    this.fxaa = new ShaderPass(FXAAShader);
    this.composer.addPass(this.fxaa);
  }

  private bindEvents() {
    const onPointerMove = (event: PointerEvent) => {
      const rect = this.canvas.getBoundingClientRect();
      this.pointer.set(((event.clientX - rect.left) / rect.width) * 2 - 1, -((event.clientY - rect.top) / rect.height) * 2 + 1);
      if (!matchMedia("(pointer: coarse)").matches) this.pointerTarget.set(this.pointer.x, this.pointer.y);
      this.pick(false);
    };
    const onPointerLeave = () => {
      this.pointer.set(3, 3);
      this.pointerTarget.set(0, 0);
      this.onSelect(null);
    };
    const onPointerDown = () => this.pick(true);
    const onVisibility = () => {
      this.visible = !document.hidden;
      if (this.visible) this.clock.getDelta();
    };
    const onResize = () => this.resize(false);
    const onLost = (event: Event) => {
      event.preventDefault();
      this.onFallback("The 3D renderer paused. Showing the static scene while it recovers.");
    };
    const onRestored = () => {
      this.contextRestoreAttempts += 1;
      if (this.contextRestoreAttempts > 1) this.onFallback("The 3D renderer could not recover reliably.");
    };
    this.canvas.addEventListener("pointermove", onPointerMove, { passive: true });
    this.canvas.addEventListener("pointerleave", onPointerLeave, { passive: true });
    this.canvas.addEventListener("pointerdown", onPointerDown, { passive: true });
    this.canvas.addEventListener("webglcontextlost", onLost);
    this.canvas.addEventListener("webglcontextrestored", onRestored);
    document.addEventListener("visibilitychange", onVisibility);
    window.addEventListener("resize", onResize, { passive: true });
    this.cleanup.push(
      () => this.canvas.removeEventListener("pointermove", onPointerMove),
      () => this.canvas.removeEventListener("pointerleave", onPointerLeave),
      () => this.canvas.removeEventListener("pointerdown", onPointerDown),
      () => this.canvas.removeEventListener("webglcontextlost", onLost),
      () => this.canvas.removeEventListener("webglcontextrestored", onRestored),
      () => document.removeEventListener("visibilitychange", onVisibility),
      () => window.removeEventListener("resize", onResize),
    );
  }

  private pick(pressed: boolean) {
    this.raycaster.layers.set(INTERACTIVE_LAYER);
    this.raycaster.setFromCamera(this.pointer, this.camera);
    const hit = this.raycaster.intersectObjects(this.interactive, true)[0];
    this.canvas.style.cursor = hit ? "pointer" : "default";
    if (!hit) return this.onSelect(null);
    let object: THREE.Object3D | null = hit.object;
    while (object && !object.userData.label) object = object.parent;
    if (object?.userData.label) {
      this.onSelect({ label: object.userData.label, detail: object.userData.detail ?? "Connected to the room" });
      if (pressed) object.scale.multiplyScalar(1.035);
    }
  }

  private resize(force: boolean) {
    const width = this.canvas.clientWidth || window.innerWidth;
    const height = this.canvas.clientHeight || window.innerHeight;
    if (!force && width === this.width && Math.abs(height - this.height) < 120) return;
    this.width = width;
    this.height = height;
    this.camera.aspect = width / Math.max(1, height);
    this.camera.updateProjectionMatrix();
    this.renderer.setSize(width, height, false);
    this.composer?.setSize(width, height);
    if (this.fxaa) {
      const dpr = this.renderer.getPixelRatio();
      this.fxaa.material.uniforms.resolution.value.set(1 / (width * dpr), 1 / (height * dpr));
    }
  }

  private update(delta: number, elapsed: number) {
    this.progress = damp(this.progress, this.targetProgress, 8.4, delta);
    this.pointerCurrent.x = damp(this.pointerCurrent.x, this.pointerTarget.x, 5.2, delta);
    this.pointerCurrent.y = damp(this.pointerCurrent.y, this.pointerTarget.y, 5.2, delta);
    const sampled = sampleBeat(this.progress, this.intervals);
    const smoothLocal = linger(sampled.local);
    const curveT = THREE.MathUtils.clamp((sampled.index + smoothLocal) / (landingConfig.beats.length - 1), 0, 1);
    this.camera.position.copy(this.cameraCurve.getPoint(curveT));
    this.camera.position.x += this.pointerCurrent.x * 0.22;
    this.camera.position.y += this.pointerCurrent.y * 0.14;
    const target = this.targetCurve.getPoint(curveT);
    this.camera.lookAt(target);
    const beat = landingConfig.beats[sampled.index];
    const next = landingConfig.beats[Math.min(sampled.index + 1, landingConfig.beats.length - 1)];
    this.camera.fov = THREE.MathUtils.lerp(beat.camera.fov, next.camera.fov, smoothLocal);
    this.camera.rotateZ(THREE.MathUtils.lerp(beat.camera.roll ?? 0, next.camera.roll ?? 0, smoothLocal));
    this.camera.updateProjectionMatrix();
    this.activeRim.position.set(target.x + 1.5, target.y + 3, target.z + 3);

    this.beatWorlds.forEach((world, index) => {
      const distance = Math.abs(index - (sampled.index + smoothLocal));
      const opacity = THREE.MathUtils.smoothstep(1.35 - distance, 0, 1);
      world.root.visible = distance < 1.8;
      world.materials.forEach((material) => setOpacity(material, opacity));
      const local = index === sampled.index ? smoothLocal : index < sampled.index ? 1 : 0;
      world.action.time = local;
      world.mixer.update(0);
      world.stage.rotation.y = Math.sin(elapsed * 0.42 + index) * 0.025;
    });

    this.shaderMaterials.forEach((material) => {
      if (material.uniforms.uTime) material.uniforms.uTime.value = elapsed;
      if (material.uniforms.uProgress) material.uniforms.uProgress.value = smoothLocal;
      if (material.uniforms.uPulse) material.uniforms.uPulse.value = 0.5 + 0.5 * Math.sin(elapsed * 2.4);
    });
    this.updatePackets(elapsed);
  }

  private updatePackets(elapsed: number) {
    if (!this.packetMesh) return;
    const matrix = new THREE.Matrix4();
    const position = new THREE.Vector3();
    const scale = new THREE.Vector3();
    const quaternion = new THREE.Quaternion();
    for (let i = 0; i < this.quality.particles; i++) {
      const t = (i / this.quality.particles + elapsed * 0.018) % 1;
      position.copy(this.routeCurve.getPointAt(t));
      position.x += Math.sin(i * 12.9898) * 0.18;
      position.y += 0.25 + Math.sin(elapsed * 1.4 + i) * 0.12;
      const s = 0.55 + ((i * 19) % 9) / 12;
      scale.setScalar(s);
      matrix.compose(position, quaternion, scale);
      this.packetMesh.setMatrixAt(i, matrix);
    }
    this.packetMesh.instanceMatrix.needsUpdate = true;
  }

  private render = () => {
    if (this.disposed) return;
    this.raf = requestAnimationFrame(this.render);
    if (!this.visible) return;
    const delta = Math.min(0.05, this.clock.getDelta());
    const elapsed = this.clock.elapsedTime;
    this.update(delta, elapsed);
    if (this.composer) this.composer.render();
    else this.renderer.render(this.scene, this.camera);
    const now = performance.now();
    const frameMs = now - this.lastFrame;
    this.lastFrame = now;
    this.slowMs = frameMs > 22 ? this.slowMs + frameMs : Math.max(0, this.slowMs - frameMs * 0.5);
    if (this.slowMs > 2000 && this.quality.tier !== "low") this.degrade();
  };

  private degrade() {
    this.slowMs = 0;
    this.quality = downgradeQuality(this.quality.tier);
    this.onQuality(this.quality.tier);
    this.renderer.setPixelRatio(Math.min(devicePixelRatio, this.quality.dpr));
    this.renderer.shadowMap.enabled = this.quality.shadows;
    if (this.quality.post === "none") {
      this.composer?.dispose();
      this.composer = null;
    }
    this.resize(true);
  }

  dispose() {
    this.disposed = true;
    cancelAnimationFrame(this.raf);
    this.cleanup.forEach((fn) => fn());
    this.mixers.forEach((mixer) => mixer.stopAllAction());
    this.scene.traverse((object) => {
      if (!(object instanceof THREE.Mesh || object instanceof THREE.InstancedMesh)) return;
      object.geometry?.dispose();
      const materials = Array.isArray(object.material) ? object.material : [object.material];
      materials.forEach((material) => {
        Object.values(material).forEach((value) => {
          if (value instanceof THREE.Texture) value.dispose();
        });
        material.dispose();
      });
    });
    this.composer?.dispose();
    this.pmrem?.dispose();
    this.scene.environment?.dispose();
    this.renderer.dispose();
    this.renderer.forceContextLoss();
  }
}
