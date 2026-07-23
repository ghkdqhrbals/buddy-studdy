export const benchmarkMetadata = {
  measuredAt: "2026-07-22",
  mvcRef: "eca7e3204177f44474c6eab3ad77340a7b0543f9",
  webfluxRef: "1b00033adf48a2c08524dfc30a7c5ad7e9efe865",
  runtimeLimit: "4 CPU · 512 MiB heap · DB pool 10",
  fixture: "1 user · 100 studies · 500 public questions",
  method: "k6 constant-arrival-rate · 5s warm-up · 15s measurement · 3 alternating rounds",
};

export const targetRps = [1000, 1500, 2000, 2500, 3000];

export const studiesSweep = {
  mvc: {
    successfulRps: [999.8, 1499.7, 1999.6, 2478.3, 2375.3],
    p95Ms: [4.08, 4.91, 13.25, 290.88, 1362.64],
    failedPercent: [0, 0, 0, 0, 0],
  },
  webflux: {
    successfulRps: [328.0, 336.5, 367.2, 345.0, 367.1],
    p95Ms: [5000.36, 5000.32, 5000.29, 5000.27, 5000.24],
    failedPercent: [37.322, 39.412, 35.014, 39.686, 35.362],
  },
};

export const studiesAt3000TimeSeries = {
  seconds: Array.from({ length: 16 }, (_, index) => index),
  mvcP95Ms: [
    169.36, 359.83, 466.95, 575.57, 673.06, 745.18, 851.64, 1054.4,
    1105.0, 1221.7, 1256.9, 1300.7, 1300.2, 1424.2, 1455.6, 1339.1,
  ],
  webfluxP95Ms: [
    755.96, 1657.1, 2545.5, 3320.4, 4048.6, 4786.1, 5000.3, 5000.1,
    4891.8, 4924.7, 4941.3, 5000.2, 5000.1, 5000.1, 4976.2, 5000.2,
  ],
};

export const diagnosticComparison = {
  targetRps: 400,
  listLimit100: {
    p95Ms: 780.94,
    allocationMibPerSecond: 1254.3,
    dbPoolPending: 259,
  },
  listLimit1: {
    p95Ms: 16.53,
    allocationMibPerSecond: 336.0,
    dbPoolPending: 5,
  },
};

export const ngrinderSmoke = {
  version: "3.5.9-p1",
  vusers: 1,
  mvc: { tps: 937.81, meanMs: 1.08, failedPercent: 0.0353 },
  webflux: { tps: 691.01, meanMs: 1.46, failedPercent: 0.048 },
  purpose: "controller, agent, script registration, execution, polling, and result normalization wiring",
};
