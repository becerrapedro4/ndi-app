// Sitio de descarga de NDI App: sirve la página y el APK de la versión publicada.
// El nombre del archivo y el hash salen del último release de GitHub, así que este
// archivo no hay que tocarlo al publicar una versión nueva.

const REPO = "becerrapedro4/ndi-app";
const RELEASE_API = `https://api.github.com/repos/${REPO}/releases/latest`;
const DOWNLOAD_PREFIX = "/download/";
const APK_TYPE = "application/vnd.android.package-archive";

// La versión se resuelve una vez por isolate y se recuerda un minuto: consultar la
// API en cada descarga agotaría su límite y, con caché de borde, la descarga podía
// anunciar el nombre de la versión anterior justo después de publicar.
const RELEASE_TTL_MS = 60_000;
let cachedRelease = null;
let cachedAt = 0;

// Respaldo si la API no responde (límite de peticiones, red caída): la última
// versión publicada y el hash de su APK.
const FALLBACK = {
  version: "1.9.6",
  sha256: "6db62ab6c3ce1a8bf47df54f68b4b289f6307dbc7592625a9c57061721c9e70b",
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (!url.pathname.startsWith(DOWNLOAD_PREFIX)) {
      return env.ASSETS.fetch(request);
    }

    const release = await currentRelease();
    const fileName = `NDI-App-${release.version}.apk`;

    // Cualquier nombre bajo /download/ (incluidos los enlaces viejos) entrega el
    // APK publicado hoy, con su nombre y su hash: así una URL antigua nunca baja
    // una versión anterior ni depende de una redirección que el navegador cachee.
    const headers = new Headers({
      "content-type": APK_TYPE,
      "content-disposition": `attachment; filename="${fileName}"`,
      // Corto: si se publica otra versión, el nombre no queda pegado al anterior.
      "cache-control": "public, max-age=60",
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

  try {
    const response = await fetch(RELEASE_API, {
      headers: {
        accept: "application/vnd.github+json",
        "user-agent": "ndi-app-site",
        "cache-control": "no-cache",
      },
    });
    if (!response.ok) return cachedRelease ?? FALLBACK;

    const data = await response.json();
    const version = String(data.tag_name ?? "").replace(/^v/, "").trim();
    if (!version) return cachedRelease ?? FALLBACK;

    const asset = (data.assets ?? []).find((item) => item?.name === `NDI-App-${version}.apk`);
    const digest = typeof asset?.digest === "string" ? asset.digest.replace(/^sha256:/i, "") : "";
    // Sin digest de GitHub solo se puede afirmar el hash de la versión de respaldo.
    const sha256 = digest || (version === FALLBACK.version ? FALLBACK.sha256 : null);

    cachedRelease = { version, sha256 };
    cachedAt = now;
    return cachedRelease;
  } catch {
    return cachedRelease ?? FALLBACK;
  }
}
