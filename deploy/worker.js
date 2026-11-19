// Sitio de descarga de NDI App: sirve la página y el APK firmado.
export default {
  async fetch(request, env) {
    const response = await env.ASSETS.fetch(request);
    const url = new URL(request.url);

    if (url.pathname === "/download/NDI-App-1.9.1.apk" && response.ok) {
      const headers = new Headers(response.headers);
      headers.set("content-type", "application/vnd.android.package-archive");
      headers.set(
        "content-disposition",
        'attachment; filename="NDI-App-1.9.1.apk"',
      );
      headers.set("cache-control", "public, max-age=300");
      headers.set(
        "x-content-sha256",
        "c5b46a445b5f82a8c1293d63af7d742683395c637691d3d95ba9af3f345b46da",
      );
      return new Response(response.body, {
        status: response.status,
        headers,
      });
    }

    return response;
  },
};
