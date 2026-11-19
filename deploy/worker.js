// Sitio de descarga de NDI App: sirve la página y el APK firmado.
export default {
  async fetch(request, env) {
    const response = await env.ASSETS.fetch(request);
    const url = new URL(request.url);

    if (url.pathname === "/download/NDI-App-1.8.0.apk" && response.ok) {
      const headers = new Headers(response.headers);
      headers.set("content-type", "application/vnd.android.package-archive");
      headers.set(
        "content-disposition",
        'attachment; filename="NDI-App-1.8.0.apk"',
      );
      headers.set("cache-control", "public, max-age=300");
      headers.set(
        "x-content-sha256",
        "0de43013bff5f2fffedb5e3cce6e0692630f6f79ba374f984a0699216c19d666",
      );
      return new Response(response.body, {
        status: response.status,
        headers,
      });
    }

    return response;
  },
};
