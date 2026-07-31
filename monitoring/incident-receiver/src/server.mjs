import { createIncidentServer, loadConfig } from "./incident-receiver.mjs";

const config = loadConfig();
const server = await createIncidentServer({ config });
server.listen(config.port, "0.0.0.0", () => {
  console.log(JSON.stringify({
    event: "incident_receiver_started",
    port: config.port,
    repository: config.githubRepository,
  }));
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
