import { Braces, Code2 } from "lucide-react";
import { useMemo, useState } from "react";
import { displayValue, parseNestedValue } from "../lib/objectInspector.js";
import { SegmentedTabs } from "./AdminUI.jsx";

function TreeNode({ name, value, depth = 0 }) {
  const composite = value && typeof value === "object";
  const [open, setOpen] = useState(depth < 2);
  if (!composite) {
    return (
      <div className="tree-leaf" style={{ "--depth": depth }}>
        <span>{name}</span>
        <code data-type={value === null ? "null" : typeof value}>{displayValue(value)}</code>
      </div>
    );
  }
  const entries = Object.entries(value);
  return (
    <div className="tree-branch">
      <button type="button" onClick={() => setOpen((current) => !current)} style={{ "--depth": depth }}>
        <span className="tree-caret">{open ? "−" : "+"}</span>
        <strong>{name}</strong>
        <small>{Array.isArray(value) ? `${entries.length} items` : `${entries.length} fields`}</small>
      </button>
      {open ? (
        <div>{entries.map(([key, item]) => (
          <TreeNode key={key} name={key} value={item} depth={depth + 1} />
        ))}</div>
      ) : null}
    </div>
  );
}

export function ObjectInspector({ value, title = "Payload" }) {
  const [mode, setMode] = useState("tree");
  const parsed = useMemo(() => parseNestedValue(value), [value]);
  return (
    <section className="object-inspector">
      <div className="object-inspector-head">
        <div><Braces size={17} /><strong>{title}</strong></div>
        <SegmentedTabs
          value={mode}
          onChange={setMode}
          ariaLabel={`${title} display`}
          items={[
            { value: "tree", label: "Tree" },
            { value: "raw", label: "Raw" },
          ]}
        />
      </div>
      {mode === "tree" ? (
        <div className="object-tree">
          <TreeNode name={title} value={parsed} />
        </div>
      ) : (
        <pre className="raw-object"><Code2 size={16} />{JSON.stringify(parsed, null, 2)}</pre>
      )}
    </section>
  );
}
