// Sitio de descarga de NDI App: sirve la página y el APK firmado.
export default {
  async fetch(request, env) {
    const response = await env.ASSETS.fetch(request);
    const url = new URL(request.url);

    if (url.pathname === "/download/NDI-App-1.9.2.apk" && response.ok) {
      const headers = new Headers(response.headers);
      headers.set("content-type", "application/vnd.android.package-archive");
      headers.set(
        "content-disposition",
        'attachment; filename="NDI-App-1.9.2.apk"',
      );
      headers.set("cache-control", "public, max-age=300");
      headers.set(
        "x-content-sha256",
        "5dd295d91549159494018865bf9c755e33eed955f8969fac84023a2234262d47",
      );
      return new Response(response.body, {
        status: response.status,
        headers,
      });
    }

    return response;
  },
};
