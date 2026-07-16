import "server-only";
import type {
  ConsoleApprovalInboxPage,
  ConsoleApprovalInboxQuery,
  ConsoleAgentCitationPage,
  ConsoleAgentConversationDetail,
  ConsoleAgentConversationPage,
  ConsoleAgentEventPage,
  ConsoleAgentMessagePage,
  ConsoleAgentPageQuery,
  ConsoleAgentRun,
  ConsoleAgentRunPage,
  CapabilitySnapshot,
  ConsoleDocumentDetail,
  ConsoleDocumentPage,
  ConsoleDocumentPageQuery,
  ConsoleSystemDoctorReport,
  ConsoleSessionProjection,
  SourceProfileSummary,
} from "@/contracts/bff";
import type { ConsoleServerConfig } from "@/server/config/schema";
import { loadConsoleServerConfig } from "@/server/config";
import { createSourceProfileRegistry } from "@/server/sources/SourceProfileRegistry";
import { cookies } from "next/headers";
import { readConsoleSession, readStoredConsoleSession } from "@/server/auth/ConsoleSessionAccess";
import {
  readApprovalInboxPage,
  readDocumentDetail,
  readDocumentPage,
  readSystemDoctorReport,
} from "@/server/dal/FlowWeftBackendClient";
import {
  readAgentCitationPage,
  readAgentConversationDetail,
  readAgentConversationPage,
  readAgentEventPage,
  readAgentMessagePage,
  readAgentRun,
  readAgentRunPage,
} from "@/server/dal/AgentWebBackendClient";

export interface ConsoleDataAccess {
  getSession(): Promise<ConsoleSessionProjection | null>;
  listSourceProfiles(): Promise<readonly SourceProfileSummary[]>;
  getCapabilitySnapshot(): Promise<CapabilitySnapshot>;
  getDocumentPage(query?: ConsoleDocumentPageQuery): Promise<ConsoleDocumentPage>;
  getDocumentDetail(documentId: string): Promise<ConsoleDocumentDetail>;
  getSystemDoctorReport(): Promise<ConsoleSystemDoctorReport>;
  getApprovalInboxPage(query?: ConsoleApprovalInboxQuery): Promise<ConsoleApprovalInboxPage>;
  getAgentConversationPage(query?: ConsoleAgentPageQuery): Promise<ConsoleAgentConversationPage>;
  getAgentConversation(conversationId: string): Promise<ConsoleAgentConversationDetail>;
  getAgentRunPage(conversationId: string, query?: ConsoleAgentPageQuery): Promise<ConsoleAgentRunPage>;
  getAgentRun(runId: string): Promise<ConsoleAgentRun>;
  getAgentMessagePage(runId: string, query?: ConsoleAgentPageQuery): Promise<ConsoleAgentMessagePage>;
  getAgentEventPage(runId: string, query?: ConsoleAgentPageQuery): Promise<ConsoleAgentEventPage>;
  getAgentCitationPage(runId: string, query?: ConsoleAgentPageQuery): Promise<ConsoleAgentCitationPage>;
}

export class ConsoleBackendUnavailableError extends Error {
  constructor() {
    super("The FlowWeft Console BFF contract is not connected.");
    this.name = "ConsoleBackendUnavailableError";
  }
}

function unavailable(): never {
  throw new ConsoleBackendUnavailableError();
}

export function createUnavailableConsoleDataAccess(): ConsoleDataAccess {
  return Object.freeze({
    getSession: async () => unavailable(),
    listSourceProfiles: async () => unavailable(),
    getCapabilitySnapshot: async () => unavailable(),
    getDocumentPage: async () => unavailable(),
    getDocumentDetail: async () => unavailable(),
    getSystemDoctorReport: async () => unavailable(),
    getApprovalInboxPage: async () => unavailable(),
    getAgentConversationPage: async () => unavailable(),
    getAgentConversation: async () => unavailable(),
    getAgentRunPage: async () => unavailable(),
    getAgentRun: async () => unavailable(),
    getAgentMessagePage: async () => unavailable(),
    getAgentEventPage: async () => unavailable(),
    getAgentCitationPage: async () => unavailable(),
  });
}

export function createConfiguredConsoleDataAccess(config: ConsoleServerConfig): ConsoleDataAccess {
  const sources = createSourceProfileRegistry(config);
  return Object.freeze({
    getSession: async () => readConsoleSession((await cookies()).get(config.sessionCookieName)?.value),
    listSourceProfiles: async () => sources.listSummaries(),
    getCapabilitySnapshot: async () => unavailable(),
    getDocumentPage: async (query = {}) => {
      const sessionId = (await cookies()).get(config.sessionCookieName)?.value;
      const session = await readStoredConsoleSession(sessionId);
      if (!session) {
        throw new ConsoleBackendUnavailableError();
      }
      return readDocumentPage(sources.requireDefinition(session.sourceProfileId), session, query);
    },
    getDocumentDetail: async (documentId: string) => {
      const sessionId = (await cookies()).get(config.sessionCookieName)?.value;
      const session = await readStoredConsoleSession(sessionId);
      if (!session) {
        throw new ConsoleBackendUnavailableError();
      }
      return readDocumentDetail(sources.requireDefinition(session.sourceProfileId), session, documentId);
    },
    getSystemDoctorReport: async () => {
      const sessionId = (await cookies()).get(config.sessionCookieName)?.value;
      const session = await readStoredConsoleSession(sessionId);
      if (!session) {
        throw new ConsoleBackendUnavailableError();
      }
      return readSystemDoctorReport(sources.requireDefinition(session.sourceProfileId), session);
    },
    getApprovalInboxPage: async (query = {}) => {
      const sessionId = (await cookies()).get(config.sessionCookieName)?.value;
      const session = await readStoredConsoleSession(sessionId);
      if (!session) {
        throw new ConsoleBackendUnavailableError();
      }
      return readApprovalInboxPage(sources.requireDefinition(session.sourceProfileId), session, query);
    },
    getAgentConversationPage: async (query = {}) => {
      const sessionId = (await cookies()).get(config.sessionCookieName)?.value;
      const session = await readStoredConsoleSession(sessionId);
      if (!session) throw new ConsoleBackendUnavailableError();
      return readAgentConversationPage(sources.requireDefinition(session.sourceProfileId), session, query);
    },
    getAgentConversation: async (conversationId: string) => {
      const sessionId = (await cookies()).get(config.sessionCookieName)?.value;
      const session = await readStoredConsoleSession(sessionId);
      if (!session) throw new ConsoleBackendUnavailableError();
      return readAgentConversationDetail(
        sources.requireDefinition(session.sourceProfileId),
        session,
        conversationId,
      );
    },
    getAgentRunPage: async (conversationId: string, query = {}) => {
      const sessionId = (await cookies()).get(config.sessionCookieName)?.value;
      const session = await readStoredConsoleSession(sessionId);
      if (!session) throw new ConsoleBackendUnavailableError();
      return readAgentRunPage(
        sources.requireDefinition(session.sourceProfileId),
        session,
        conversationId,
        query,
      );
    },
    getAgentRun: async (runId: string) => {
      const sessionId = (await cookies()).get(config.sessionCookieName)?.value;
      const session = await readStoredConsoleSession(sessionId);
      if (!session) throw new ConsoleBackendUnavailableError();
      return readAgentRun(sources.requireDefinition(session.sourceProfileId), session, runId);
    },
    getAgentMessagePage: async (runId: string, query = {}) => {
      const sessionId = (await cookies()).get(config.sessionCookieName)?.value;
      const session = await readStoredConsoleSession(sessionId);
      if (!session) throw new ConsoleBackendUnavailableError();
      return readAgentMessagePage(sources.requireDefinition(session.sourceProfileId), session, runId, query);
    },
    getAgentEventPage: async (runId: string, query = {}) => {
      const sessionId = (await cookies()).get(config.sessionCookieName)?.value;
      const session = await readStoredConsoleSession(sessionId);
      if (!session) throw new ConsoleBackendUnavailableError();
      return readAgentEventPage(sources.requireDefinition(session.sourceProfileId), session, runId, query);
    },
    getAgentCitationPage: async (runId: string, query = {}) => {
      const sessionId = (await cookies()).get(config.sessionCookieName)?.value;
      const session = await readStoredConsoleSession(sessionId);
      if (!session) throw new ConsoleBackendUnavailableError();
      return readAgentCitationPage(sources.requireDefinition(session.sourceProfileId), session, runId, query);
    },
  });
}

let configuredDataAccess: ConsoleDataAccess | undefined;

export function getConsoleDataAccess(): ConsoleDataAccess {
  configuredDataAccess ??= createConfiguredConsoleDataAccess(loadConsoleServerConfig());
  return configuredDataAccess;
}
