import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Cable, Database, KeyRound, LayoutDashboard, ListChecks, LogOut, Settings } from "lucide-react";
import { useState, type ReactNode } from "react";
import { NavLink } from "react-router-dom";
import { api, call } from "@/api/client";
import { keys, useMe } from "@/api/queries";
import { cn } from "@/lib/format";
import { ChangePasswordForm } from "./ChangePasswordForm";
import { Dialog } from "./ui/dialog";

const nav = [
  { to: "/", label: "Dashboard", icon: LayoutDashboard, end: true },
  { to: "/jobs", label: "Jobs", icon: ListChecks },
  { to: "/connections", label: "Connections", icon: Cable },
  { to: "/settings", label: "Settings", icon: Settings },
];

export function AppLayout({ children }: { children: ReactNode }) {
  const me = useMe().data;
  const queryClient = useQueryClient();
  const [changingPassword, setChangingPassword] = useState(false);
  const logout = useMutation({
    mutationFn: () => call(api.POST("/api/v1/auth/logout")),
    onSettled: () => {
      queryClient.clear();
      queryClient.setQueryData(keys.me, null);
    },
  });

  return (
    <div className="min-h-screen md:grid md:grid-cols-[14rem_1fr]">
      <aside className="flex flex-col border-b border-border bg-surface md:sticky md:top-0 md:h-screen md:border-r md:border-b-0">
        <div className="flex items-center gap-2 px-4 py-4 font-semibold">
          <Database className="size-5 text-primary" aria-hidden />
          DataSync
        </div>
        <nav className="flex gap-1 overflow-x-auto px-2 pb-2 md:flex-1 md:flex-col md:pb-0">
          {nav.map(({ to, label, icon: Icon, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              className={({ isActive }) =>
                cn(
                  "flex items-center gap-2.5 rounded-md px-3 py-2 text-sm whitespace-nowrap text-muted-foreground hover:bg-muted hover:text-foreground",
                  isActive && "bg-muted font-medium text-foreground",
                )
              }
            >
              <Icon className="size-4" aria-hidden />
              {label}
            </NavLink>
          ))}
        </nav>
        <div className="hidden border-t border-border p-3 md:block">
          <p className="truncate px-1 text-sm font-medium">{me?.username}</p>
          <p className="px-1 text-xs text-muted-foreground capitalize">{me?.role.toLowerCase()}</p>
          <div className="mt-2 grid gap-1">
            <button
              className="flex items-center gap-2 rounded-md px-1 py-1.5 text-sm text-muted-foreground hover:text-foreground"
              onClick={() => setChangingPassword(true)}
            >
              <KeyRound className="size-4" aria-hidden /> Change password
            </button>
            <button
              className="flex items-center gap-2 rounded-md px-1 py-1.5 text-sm text-muted-foreground hover:text-foreground"
              onClick={() => logout.mutate()}
            >
              <LogOut className="size-4" aria-hidden /> Sign out
            </button>
          </div>
        </div>
      </aside>
      <main className="min-w-0 px-4 py-6 md:px-8 md:py-8">
        <div className="mx-auto max-w-6xl">{children}</div>
        <div className="mt-10 flex gap-4 border-t border-border pt-4 text-sm text-muted-foreground md:hidden">
          <span>{me?.username}</span>
          <button onClick={() => setChangingPassword(true)}>Change password</button>
          <button onClick={() => logout.mutate()}>Sign out</button>
        </div>
      </main>
      <Dialog open={changingPassword} onOpenChange={setChangingPassword} title="Change password">
        <div className="pb-5">
          <ChangePasswordForm onDone={() => setChangingPassword(false)} />
        </div>
      </Dialog>
    </div>
  );
}
