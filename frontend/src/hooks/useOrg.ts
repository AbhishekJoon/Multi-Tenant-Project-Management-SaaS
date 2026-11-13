import { useOutletContext } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import type { Organization } from "../types";
export function useOrg() {
  return useOutletContext<Organization>();
}
export function useRefreshOrg() {
  const org = useOrg();
  const cache = useQueryClient();
  return () => cache.invalidateQueries({ queryKey: ["org", org.id] });
}
