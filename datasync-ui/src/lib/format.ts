import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

const numbers = new Intl.NumberFormat();
const relative = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });
const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "medium" });

export function formatNumber(n: number) {
  return numbers.format(n);
}

export function formatDateTime(iso: string) {
  return dateTime.format(new Date(iso));
}

/** "3 minutes ago", "yesterday". */
export function formatRelative(iso: string, now = Date.now()) {
  const seconds = Math.round((new Date(iso).getTime() - now) / 1000);
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ["day", 86_400],
    ["hour", 3_600],
    ["minute", 60],
  ];
  for (const [unit, size] of units) {
    if (Math.abs(seconds) >= size) return relative.format(Math.round(seconds / size), unit);
  }
  return relative.format(seconds, "second");
}

/** "850 ms", "4.2 s", "3 m 12 s". */
export function formatDuration(fromIso: string, toIso: string | null | undefined) {
  if (!toIso) return "—";
  const ms = new Date(toIso).getTime() - new Date(fromIso).getTime();
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  return `${Math.floor(ms / 60_000)} m ${Math.round((ms % 60_000) / 1000)} s`;
}
