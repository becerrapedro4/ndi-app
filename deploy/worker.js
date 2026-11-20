// Sitio de descarga de NDI App: sirve la página y el APK de la versión publicada.
// El nombre del archivo y el hash salen del último release de GitHub, así que este
// archivo no hay que tocarlo al publicar una versión nueva.

const REPO = "becerrapedro4/ndi-app";
const LATEST_PAGE = `https://github.com/${REPO}/releases/latest`;
const RELEASE_API = `https://api.github.com/repos/${REPO}/releases/latest`;
const DOWNLOAD_PREFIX = "/download/";
const APK_TYPE = "application/vnd.android.package-archive";

// La versión se recuerda un minuto por isolate. Se resuelve leyendo la redirección
// de /releases/latest, que no tiene límite de peticiones: la API de GitHub comparte
// la cuota por IP y desde la red del Worker se agota, y aquel respaldo fijo era la
// causa de que algunas descargas entregaran el APK de una versión vieja.
const RELEASE_TTL_MS = 60_000;
let cachedRelease = null;
let cachedAt = 0;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (!url.pathname.startsWith(DOWNLOAD_PREFIX)) {
      return env.ASSETS.fetch(request);
    }

    const release = await currentRelease();
    if (!release) {
      return new Response("No se pudo resolver la versión publicada.\n", {
        status: 502,
        headers: { "content-type": "text/plain; charset=utf-8" },
      });
    }
    const fileName = `NDI-App-${release.version}.apk`;

    // Cualquier nombre bajo /download/ (incluidos los enlaces viejos) entrega el
    // APK publicado hoy, con su nombre y su hash: así una URL antigua nunca baja
    // una versión anterior ni depende de una redirección que el navegador cachee.
    const headers = new Headers({
      "content-type": APK_TYPE,
      "content-disposition": `attachment; filename="${fileName}"`,
      // Sin caché: al publicar una versión nueva ningún borde puede seguir
      // entregando el APK anterior bajo el nombre nuevo.
      "cache-control": "no-store",
    });
    if (release.sha256) headers.set("x-content-sha256", release.sha256);

    const head = request.method === "HEAD";
    const upstream = await fetch(
      `https://github.com/${REPO}/releases/download/v${release.version}/${fileName}`,
      { method: head ? "HEAD" : "GET", redirect: "follow" },
    );
    if (!upstream.ok) {
      return new Response("No se pudo descargar el APK del release publicado.\n", {
        status: 502,
        headers: { "content-type": "text/plain; charset=utf-8" },
      });
    }

    return new Response(head ? null : upstream.body, { status: 200, headers });
  },
};

/** Último release publicado: versión del tag y hash del APK que lo acompaña. */
async function currentRelease() {
  const now = Date.now();
  if (cachedRelease && now - cachedAt < RELEASE_TTL_MS) return cachedRelease;

  let version = await latestTag();
  let sha256 = null;
  if (!version) {
    const api = await apiRelease();
    version = api?.version ?? cachedRelease?.version ?? null;
    sha256 = api?.sha256 ?? null;
  }
  if (!version) return cachedRelease ?? null;
  if (!sha256) sha256 = await apiDigest(version);

  cachedRelease = { version, sha256 };
  cachedAt = now;
  return cachedRelease;
}

/** Versión del último release sin gastar cuota: el tag va en la redirección. */
async function latestTag() {
  try {
    const response = await fetch(LATEST_PAGE, { method: "HEAD", redirect: "manual" });
    const location = response.headers.get("location") ?? "";
    const match = location.match(/\/releases\/tag\/v?([^/?#]+)/);
    if (match) return decodeURIComponent(match[1]).trim() || null;
  } catch {
    // Sin redirección se prueba la API.
  }
  return null;
}

/** Respaldo con la API (puede estar limitada): versión y hash del APK. */
async function apiRelease() {
  try {
    const response = await fetch(RELEASE_API, {
      headers: {
        accept: "application/vnd.github+json",
        "user-agent": "ndi-app-site",
        "cache-control": "no-cache",
      },
    });
    if (!response.ok) return null;
    const data = await response.json();
    const version = String(data.tag_name ?? "").replace(/^v/, "").trim();
    if (!version) return null;
    return { version, sha256: digestOf(data, version) };
  } catch {
    return null;
  }
}

/** Hash del APK, sólo informativo: si la API no responde se omite la cabecera. */
async function apiDigest(version) {
  try {
    const response = await fetch(`https://api.github.com/repos/${REPO}/releases/tags/v${version}`, {
      headers: { accept: "application/vnd.github+json", "user-agent": "ndi-app-site" },
    });
    if (!response.ok) return null;
    return digestOf(await response.json(), version);
  } catch {
    return null;
  }
}

function digestOf(data, version) {
  const asset = (data.assets ?? []).find((item) => item?.name === `NDI-App-${version}.apk`);
  if (typeof asset?.digest !== "string") return null;
  const digest = asset.digest.replace(/^sha256:/i, "").trim();
  return digest || null;
}
