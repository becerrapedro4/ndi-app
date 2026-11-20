// Sitio de descarga de NDI App: sirve la página y el APK de la versión publicada.
// El nombre del archivo y el hash salen del último release de GitHub, así que este
// archivo no hay que tocarlo al publicar una versión nueva.

const REPO = "becerrapedro4/ndi-app";
const RELEASE_API = `https://api.github.com/repos/${REPO}/releases/latest`;
const DOWNLOAD_PREFIX = "/download/";
const APK_TYPE = "application/vnd.android.package-archive";

// Respaldo si la API no responde (límite de peticiones, red caída): la última
// versión publicada y el hash de su APK.
const FALLBACK = {
  version: "1.9.5",
  sha256: "3586f03240f1b92e1c50ec1376a150238e5a166aef637c582ca6dae7bd41a8fd",
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (!url.pathname.startsWith(DOWNLOAD_PREFIX)) {
      return env.ASSETS.fetch(request);
    }

    const release = await currentRelease();
    const fileName = `NDI-App-${release.version}.apk`;
    const canonical = new URL(`${DOWNLOAD_PREFIX}${fileName}`, url);

    // Enlaces antiguos con el nombre de otra versión: se redirigen al actual para
    // que sigan bajando el APK publicado y no un archivo viejo.
    if (url.pathname !== canonical.pathname) {
      return Response.redirect(canonical.toString(), 301);
    }

    const headers = new Headers({
      "content-type": APK_TYPE,
      "content-disposition": `attachment; filename="${fileName}"`,
      "cache-control": "public, max-age=300",
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
  try {
    const response = await fetch(RELEASE_API, {
      headers: {
        accept: "application/vnd.github+json",
        "user-agent": "ndi-app-site",
      },
      cf: { cacheTtl: 600, cacheEverything: true },
    });
    if (!response.ok) return FALLBACK;

    const data = await response.json();
    const version = String(data.tag_name ?? "").replace(/^v/, "").trim();
    if (!version) return FALLBACK;

    const asset = (data.assets ?? []).find((item) => item?.name === `NDI-App-${version}.apk`);
    const digest = typeof asset?.digest === "string" ? asset.digest.replace(/^sha256:/i, "") : "";
    // Sin digest de GitHub solo se puede afirmar el hash de la versión de respaldo.
    const sha256 = digest || (version === FALLBACK.version ? FALLBACK.sha256 : null);
    return { version, sha256 };
  } catch {
    return FALLBACK;
  }
}
