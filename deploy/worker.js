// Sitio de descarga de NDI App: sirve la página y el APK firmado.
export default {
  async fetch(request, env) {
    const response = await env.ASSETS.fetch(request);
    const url = new URL(request.url);

    if (url.pathname === "/download/NDI-App-1.9.0.apk" && response.ok) {
      const headers = new Headers(response.headers);
      headers.set("content-type", "application/vnd.android.package-archive");
      headers.set(
        "content-disposition",
        'attachment; filename="NDI-App-1.9.0.apk"',
      );
      headers.set("cache-control", "public, max-age=300");
      headers.set(
        "x-content-sha256",
        "887a9396334d6040a238c63366f36570f9de6862fc27e13b1304ef6876f96a10",
      );
      return new Response(response.body, {
        status: response.status,
        headers,
      });
    }

    return response;
  },
};
