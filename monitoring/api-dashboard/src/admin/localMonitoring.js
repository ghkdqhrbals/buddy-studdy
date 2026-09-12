// Opt-in applies only to a separate local build served on the loopback host.
export function allowsLocalMonitoring(enabled, hostname) {
  return enabled === "true" && ["localhost", "127.0.0.1", "[::1]", "::1"].includes(hostname);
}
