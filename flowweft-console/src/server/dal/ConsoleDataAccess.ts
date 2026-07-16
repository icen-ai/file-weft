import "server-only";
import type {
  ConsoleApprovalInboxPage,
  ConsoleApprovalInboxQuery,
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

export interface ConsoleDataAccess {
  getSession(): Promise<ConsoleSessionProjection | null>;
  listSourceProfiles(): Promise<readonly SourceProfileSummary[]>;
  getCapabilitySnapshot(): Promise<CapabilitySnapshot>;
  getDocumentPage(query?: ConsoleDocumentPageQuery): Promise<ConsoleDocumentPage>;
  getDocumentDetail(documentId: string): Promise<ConsoleDocumentDetail>;
  getSystemDoctorReport(): Promise<ConsoleSystemDoctorReport>;
  getApprovalInboxPage(query?: ConsoleApprovalInboxQuery): Promise<ConsoleApprovalInboxPage>;
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
  });
}

let configuredDataAccess: ConsoleDataAccess | undefined;

export function getConsoleDataAccess(): ConsoleDataAccess {
  configuredDataAccess ??= createConfiguredConsoleDataAccess(loadConsoleServerConfig());
  return configuredDataAccess;
}
