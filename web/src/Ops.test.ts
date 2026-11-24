import { describe, expect, it } from "vitest";
import { ACTION_LABELS, visibleTabsFor, type OpsPermissions } from "./Ops";

const ROLE_PERMISSIONS: Record<string, OpsPermissions> = {
  support_agent: {
    canApproveMakerChecker: false,
    canFreezeRisk: false,
    canManageFinance: false,
    canOnboardMerchants: false,
    canManageSupport: true,
  },
  onboarding_officer: {
    canApproveMakerChecker: false,
    canFreezeRisk: false,
    canManageFinance: false,
    canOnboardMerchants: true,
    canManageSupport: false,
  },
  finance_ops: {
    canApproveMakerChecker: false,
    canFreezeRisk: false,
    canManageFinance: true,
    canOnboardMerchants: false,
    canManageSupport: false,
  },
  risk_compliance: {
    canApproveMakerChecker: false,
    canFreezeRisk: true,
    canManageFinance: false,
    canOnboardMerchants: true,
    canManageSupport: true,
  },
  ops_admin: {
    canApproveMakerChecker: true,
    canFreezeRisk: true,
    canManageFinance: true,
    canOnboardMerchants: true,
    canManageSupport: true,
  },
  ops: {
    canApproveMakerChecker: false,
    canFreezeRisk: true,
    canManageFinance: true,
    canOnboardMerchants: true,
    canManageSupport: true,
  },
};

const MAKER_CHECKER_ACTION_TYPES = [
  "KILL_SWITCH_CHANGE",
  "MERCHANT_UNFREEZE",
  "MODULE_ENABLE",
  "DISPUTE_RESOLUTION",
  "FEE_SCHEDULE_CHANGE",
  "CUSTODIAN_CONFIG",
  "LARGE_PAYOUT",
  "ESCROW_RELEASE",
  "FORCE_SETTLEMENT",
  "SETTLEMENT_RULE_CHANGE",
  "DESTINATION_OPS_VERIFY",
  "VELOCITY_CEILING_CHANGE",
  "PHONE_CHANGE",
] as const;

describe("Ops.tsx permissions and labels", () => {
  describe("visibleTabsFor", () => {
    it("fails closed with an empty tab list when permissions are null", () => {
      expect(visibleTabsFor(null)).toEqual([]);
    });

    it("restricts support_agent to support-scoped tabs", () => {
      expect(visibleTabsFor(ROLE_PERMISSIONS.support_agent)).toEqual([
        "Overview",
        "Merchant Controls",
        "Disputes",
        "Exceptions",
        "System Health",
      ]);
    });

    it("grants Onboarding tab to onboarding_officer without finance or kill switches", () => {
      const tabs = visibleTabsFor(ROLE_PERMISSIONS.onboarding_officer);
      expect(tabs).toEqual([
        "Overview",
        "Merchant Controls",
        "Onboarding",
        "Disputes",
        "Exceptions",
        "System Health",
      ]);
      expect(tabs).not.toContain("Kill Switches");
      expect(tabs).not.toContain("Custodian Trust");
      expect(tabs).not.toContain("Maker-Checker");
    });

    it("grants Maker-Checker, Custodian Trust, and Reconciliation to finance_ops", () => {
      const tabs = visibleTabsFor(ROLE_PERMISSIONS.finance_ops);
      expect(tabs).toEqual([
        "Overview",
        "Merchant Controls",
        "Maker-Checker",
        "Custodian Trust",
        "Disputes",
        "Reconciliation",
        "Exceptions",
        "System Health",
      ]);
      expect(tabs).not.toContain("Kill Switches");
      expect(tabs).not.toContain("Onboarding");
    });

    it("grants Maker-Checker, Kill Switches, and Onboarding to risk_compliance without Custodian Trust or Reconciliation", () => {
      const tabs = visibleTabsFor(ROLE_PERMISSIONS.risk_compliance);
      expect(tabs).toEqual([
        "Overview",
        "Merchant Controls",
        "Maker-Checker",
        "Kill Switches",
        "Onboarding",
        "Disputes",
        "Exceptions",
        "System Health",
      ]);
      expect(tabs).not.toContain("Custodian Trust");
      expect(tabs).not.toContain("Reconciliation");
    });

    it("grants all 10 tabs to ops_admin and ops", () => {
      const expectedAll = [
        "Overview",
        "Merchant Controls",
        "Maker-Checker",
        "Kill Switches",
        "Custodian Trust",
        "Onboarding",
        "Disputes",
        "Reconciliation",
        "Exceptions",
        "System Health",
      ];
      expect(visibleTabsFor(ROLE_PERMISSIONS.ops_admin)).toEqual(expectedAll);
      expect(visibleTabsFor(ROLE_PERMISSIONS.ops)).toEqual(expectedAll);
    });
  });

  describe("ACTION_LABELS", () => {
    it("covers all 13 MakerCheckerService.ACTION_TYPES with non-empty human-readable labels", () => {
      for (const actionType of MAKER_CHECKER_ACTION_TYPES) {
        const label = ACTION_LABELS[actionType];
        expect(typeof label).toBe("string");
        expect(label.trim().length).toBeGreaterThan(0);
        expect(label).not.toBe(actionType);
      }
      expect(Object.keys(ACTION_LABELS).sort()).toEqual([...MAKER_CHECKER_ACTION_TYPES].sort());
    });
  });
});
