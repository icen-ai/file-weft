import { notFound } from "next/navigation";
import type {
  ConsoleAgentCitationPage,
  ConsoleAgentConversationDetail,
  ConsoleAgentConversationPage,
  ConsoleAgentEventPage,
  ConsoleAgentMessagePage,
  ConsoleAgentRun,
  ConsoleAgentRunPage,
} from "@/contracts/bff";
import { AgentWorkbench, AgentWorkbenchUnavailable } from "@/features/agent/AgentWorkbench";
import { isLocale } from "@/i18n/locale";
import { getConsoleDataAccess } from "@/server/dal/ConsoleDataAccess";

interface AgentPageProps {
  readonly params: Promise<{ locale: string }>;
  readonly searchParams: Promise<{
    conversationId?: string | string[];
    runId?: string | string[];
    conversationCursor?: string | string[];
    runCursor?: string | string[];
    messageCursor?: string | string[];
    eventCursor?: string | string[];
    citationCursor?: string | string[];
  }>;
}

export default async function AgentPage({ params, searchParams }: AgentPageProps) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  const raw = await searchParams;
  if (Object.values(raw).some(Array.isArray)) return <AgentWorkbenchUnavailable locale={locale} />;

  const conversationId = raw.conversationId as string | undefined;
  const runId = raw.runId as string | undefined;
  const cursors = {
    conversation: raw.conversationCursor as string | undefined,
    run: raw.runCursor as string | undefined,
    message: raw.messageCursor as string | undefined,
    event: raw.eventCursor as string | undefined,
    citation: raw.citationCursor as string | undefined,
  };
  const dataAccess = getConsoleDataAccess();
  let conversations: ConsoleAgentConversationPage;
  try {
    conversations = await dataAccess.getAgentConversationPage({
      ...(cursors.conversation ? { cursor: cursors.conversation } : {}),
      limit: 30,
    });
  } catch {
    return <AgentWorkbenchUnavailable locale={locale} />;
  }

  let conversation: ConsoleAgentConversationDetail | null = null;
  let runs: ConsoleAgentRunPage | null = null;
  let run: ConsoleAgentRun | null = null;
  let messages: ConsoleAgentMessagePage | null = null;
  let events: ConsoleAgentEventPage | null = null;
  let citations: ConsoleAgentCitationPage | null = null;
  let selectedConversationUnavailable = false;
  let selectedRunUnavailable = Boolean(runId && !conversationId);

  if (conversationId) {
    try {
      [conversation, runs] = await Promise.all([
        dataAccess.getAgentConversation(conversationId),
        dataAccess.getAgentRunPage(conversationId, {
          ...(cursors.run ? { cursor: cursors.run } : {}),
          limit: 25,
        }),
      ]);
    } catch {
      selectedConversationUnavailable = true;
    }
  }

  if (conversation && runs && runId) {
    try {
      const selectedRun = await dataAccess.getAgentRun(runId);
      if (selectedRun.conversationId !== conversation.summary.id) {
        throw new Error("Agent run is outside the selected conversation.");
      }
      [messages, events, citations] = await Promise.all([
        dataAccess.getAgentMessagePage(selectedRun.id, {
          ...(cursors.message ? { cursor: cursors.message } : {}),
          limit: 10,
        }),
        dataAccess.getAgentEventPage(selectedRun.id, {
          ...(cursors.event ? { cursor: cursors.event } : {}),
          limit: 40,
        }),
        dataAccess.getAgentCitationPage(selectedRun.id, {
          ...(cursors.citation ? { cursor: cursors.citation } : {}),
          limit: 30,
        }),
      ]);
      run = selectedRun;
    } catch {
      run = null;
      messages = null;
      events = null;
      citations = null;
      selectedRunUnavailable = true;
    }
  }

  return <AgentWorkbench
    locale={locale}
    conversations={conversations}
    conversation={conversation}
    runs={runs}
    run={run}
    messages={messages}
    events={events}
    citations={citations}
    selectedConversationUnavailable={selectedConversationUnavailable}
    selectedRunUnavailable={selectedRunUnavailable}
    cursors={cursors}
  />;
}
