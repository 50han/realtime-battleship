// The protocol test only exercises the reducer, which is pure. React's hooks
// are never called, so a stub is enough to satisfy the module's imports.
const unused = () => { throw new Error("React hooks are not available in the protocol test"); };
export const useCallback = unused;
export const useEffect = unused;
export const useReducer = unused;
export const useRef = unused;
export const useState = unused;
export default { useCallback, useEffect, useReducer, useRef, useState };
