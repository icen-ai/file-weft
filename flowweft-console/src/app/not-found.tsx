import Link from "next/link";

export default function NotFound() {
  return (
    <main className="not-found">
      <span>404 / LOOSE THREAD</span>
      <h1>这根线没有连接到页面。</h1>
      <p>The requested thread is not part of the FlowWeft Console weave.</p>
      <Link href="/zh">返回 Console / Return to Console</Link>
    </main>
  );
}
