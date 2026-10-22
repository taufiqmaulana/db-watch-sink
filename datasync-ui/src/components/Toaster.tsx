import { CheckCircle2, X, XCircle } from "lucide-react";
import { useSyncExternalStore } from "react";
import { ApiError } from "@/api/client";

interface Toast {
  id: number;
  kind: "success" | "error";
  message: string;
  detail?: string | null;
}

let toasts: Toast[] = [];
let nextId = 1;
const listeners = new Set<() => void>();

function emit(next: Toast[]) {
  toasts = next;
  listeners.forEach((l) => l());
}

function dismiss(id: number) {
  emit(toasts.filter((t) => t.id !== id));
}

function push(t: Omit<Toast, "id">, ttlMs: number) {
  const id = nextId++;
  emit([...toasts, { ...t, id }]);
  setTimeout(() => dismiss(id), ttlMs);
}

/** Feedback for actions that have no form of their own (run, pause, delete). */
export const toast = {
  success: (message: string) => push({ kind: "success", message }, 4_000),
  error: (error: unknown) =>
    push(
      {
        kind: "error",
        message: error instanceof Error ? error.message : String(error),
        detail: error instanceof ApiError ? error.detail : null,
      },
      10_000,
    ),
};

export function Toaster() {
  const items = useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => toasts,
  );
  return (
    <div className="fixed right-4 bottom-4 z-50 flex w-[min(24rem,calc(100vw-2rem))] flex-col gap-2" aria-live="polite">
      {items.map((t) => (
        <div key={t.id} role="status" className="flex gap-2 rounded-lg border border-border bg-surface p-3 text-sm shadow-lg">
          {t.kind === "success" ? (
            <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-good-text" aria-hidden />
          ) : (
            <XCircle className="mt-0.5 size-4 shrink-0 text-critical-text" aria-hidden />
          )}
          <div className="min-w-0 flex-1">
            <p>{t.message}</p>
            {t.detail && t.detail !== t.message && <p className="mt-1 font-mono text-xs break-words text-muted-foreground">{t.detail}</p>}
          </div>
          <button className="self-start rounded p-0.5 text-muted-foreground hover:bg-muted" onClick={() => dismiss(t.id)} aria-label="Dismiss">
            <X className="size-4" />
          </button>
        </div>
      ))}
    </div>
  );
}
