import { AlertTriangle, CheckCircle2, CircleDashed, Loader2, PauseCircle, XCircle, type LucideIcon } from "lucide-react";
import type { RunView, Schemas } from "@/api/client";
import { cn } from "@/lib/format";

type Tone = "good" | "warning" | "critical" | "neutral" | "info";

const tones: Record<Tone, string> = {
  good: "text-good-text",
  warning: "text-warning-text",
  critical: "text-critical-text",
  neutral: "text-muted-foreground",
  info: "text-primary",
};

/** Status is always an icon plus a label, never color alone. */
function Badge({ tone, icon: Icon, label, spin }: { tone: Tone; icon: LucideIcon; label: string; spin?: boolean }) {
  return (
    <span className={cn("inline-flex items-center gap-1.5 text-sm font-medium whitespace-nowrap", tones[tone])}>
      <Icon className={cn("size-4", spin && "animate-spin")} aria-hidden />
      {label}
    </span>
  );
}

export function JobStatusBadge({ status, running }: { status: Schemas["Status"]; running?: boolean }) {
  if (running) return <Badge tone="info" icon={Loader2} label="Running" spin />;
  switch (status) {
    case "ACTIVE":
      return <Badge tone="good" icon={CheckCircle2} label="Active" />;
    case "PAUSED":
      return <Badge tone="neutral" icon={PauseCircle} label="Paused" />;
    case "NEEDS_REVIEW":
      return <Badge tone="warning" icon={AlertTriangle} label="Needs review" />;
  }
}

export function RunStateBadge({ run }: { run: Pick<RunView, "state"> | null | undefined }) {
  switch (run?.state) {
    case undefined:
      return <Badge tone="neutral" icon={CircleDashed} label="Never run" />;
    case "RUNNING":
      return <Badge tone="info" icon={Loader2} label="Running" spin />;
    case "SUCCEEDED":
      return <Badge tone="good" icon={CheckCircle2} label="Succeeded" />;
    case "FAILED":
      return <Badge tone="critical" icon={XCircle} label="Failed" />;
    case "NEEDS_REVIEW":
      return <Badge tone="warning" icon={AlertTriangle} label="Needs review" />;
  }
}
