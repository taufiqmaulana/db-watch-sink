import * as RadixTabs from "@radix-ui/react-tabs";
import type { ReactNode } from "react";

export function Tabs({ tabs, defaultValue }: { tabs: { value: string; label: string; content: ReactNode }[]; defaultValue?: string }) {
  return (
    <RadixTabs.Root defaultValue={defaultValue ?? tabs[0]?.value}>
      <RadixTabs.List className="mb-4 flex gap-1 overflow-x-auto border-b border-border">
        {tabs.map((t) => (
          <RadixTabs.Trigger
            key={t.value}
            value={t.value}
            className="-mb-px border-b-2 border-transparent px-3 py-2 text-sm whitespace-nowrap text-muted-foreground hover:text-foreground data-[state=active]:border-primary data-[state=active]:font-medium data-[state=active]:text-foreground"
          >
            {t.label}
          </RadixTabs.Trigger>
        ))}
      </RadixTabs.List>
      {tabs.map((t) => (
        <RadixTabs.Content key={t.value} value={t.value} className="focus-visible:outline-none">
          {t.content}
        </RadixTabs.Content>
      ))}
    </RadixTabs.Root>
  );
}
