/** A unified diff with added, removed and hunk lines colored. */
export function Diff({ diff }: { diff: string }) {
  return (
    <pre className="diff">
      {diff.split("\n").map((line, i) => (
        <div key={i} className={kind(line)}>
          {line || " "}
        </div>
      ))}
    </pre>
  );
}

function kind(line: string) {
  if (line.startsWith("+++") || line.startsWith("---")) return "file";
  if (line.startsWith("@@")) return "hunk";
  if (line.startsWith("+")) return "add";
  if (line.startsWith("-")) return "del";
  return undefined;
}
