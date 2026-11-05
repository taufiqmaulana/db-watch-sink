import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { JobView } from "@/api/client";
import { JobFlowDiagram } from "./JobFlowDiagram";

const sink = (id: string, over: Partial<JobView["sinks"][number]> = {}): JobView["sinks"][number] => ({
  id,
  label: id,
  status: "ACTIVE",
  backlog: 0,
  attempts: 0,
  nextRetryAt: null,
  lastError: null,
  deliveredAt: null,
  delivering: false,
  loaded: true,
  ...over,
});

const job = (over: Partial<JobView> = {}) =>
  ({
    id: "j1",
    name: "orders",
    status: "ACTIVE",
    version: 1,
    running: true,
    lastRun: null,
    nextRunAt: null,
    nextReconcileAt: null,
    watermarkValue: "2026-11-05T10:00:00Z",
    periodSeconds: 60,
    config: {
      source: { connection: "c1", object: "SALES.ORDERS" },
      mode: "WATERMARK",
      sinks: [
        { type: "jdbc", connection: "c2", target: "public.orders" },
        { type: "webhook", connection: "c3" },
        { type: "jdbc", connection: "c2", target: "archive.orders" },
      ],
    },
    sinks: [
      sink("warehouse", { delivering: true, backlog: 4 }),
      sink("hook", { attempts: 2, backlog: 1, lastError: "HTTP 503" }),
      sink("archive", { status: "PAUSED" }),
    ],
    createdAt: "",
    updatedAt: "",
    ...over,
  }) as unknown as JobView;

function renderDiagram(j: JobView) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <JobFlowDiagram job={j} />
    </QueryClientProvider>,
  );
}

describe("JobFlowDiagram", () => {
  beforeEach(() => vi.stubGlobal("fetch", () => new Promise(() => {})));
  afterEach(() => vi.unstubAllGlobals());

  it("shows every stage and each sink's status", () => {
    const { container } = renderDiagram(job());
    expect(screen.getByText("Reading")).toBeInTheDocument();
    expect(screen.getByText("5 batches queued")).toBeInTheDocument();
    expect(screen.getByText("Delivering")).toBeInTheDocument();
    expect(screen.getByText("Retrying")).toBeInTheDocument();
    expect(screen.getByText("Paused")).toBeInTheDocument();
    expect(screen.getByText("4 waiting")).toBeInTheDocument();
    // reading, writing, the delivering sink and the retrying sink animate; the paused sink does not
    expect(container.querySelectorAll(".flow-dots")).toHaveLength(4);
    expect(container.querySelectorAll(".flow-dots-slow")).toHaveLength(1);
  });

  it("does not animate an idle job", () => {
    const { container } = renderDiagram(job({ running: false, sinks: [sink("warehouse"), sink("hook"), sink("archive")] }));
    expect(container.querySelectorAll(".flow-dots")).toHaveLength(0);
    expect(screen.getByText("Empty")).toBeInTheDocument();
    expect(screen.getAllByText("Up to date")).toHaveLength(3);
  });
});
