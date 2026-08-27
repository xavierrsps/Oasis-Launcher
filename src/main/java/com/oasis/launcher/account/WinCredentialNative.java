package com.oasis.launcher.account;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Structure.FieldOrder;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Direct JNA bindings for Windows Advapi32 credential APIs.
 *
 * <p>Wraps {@code CredWriteW}, {@code CredReadW}, {@code CredDeleteW} and
 * {@code CredFree} — the Win32 functions that back the Credential Manager.
 *
 * <p>This is a minimal binding scoped to the launcher's needs. JNA-Platform
 * does provide an {@code Advapi32} interface but the credential subset is
 * spotty across JNA versions, so we declare what we use here.
 */
final class WinCredentialNative {

    private WinCredentialNative() {}

    static final int CRED_TYPE_GENERIC = 1;
    static final int CRED_PERSIST_LOCAL_MACHINE = 2;

    /**
     * Represents the Win32 {@code CREDENTIALW} struct.
     */
    @FieldOrder({
            "Flags", "Type", "TargetName", "Comment", "LastWritten",
            "CredentialBlobSize", "CredentialBlob", "Persist",
            "AttributeCount", "Attributes", "TargetAlias", "UserName"
    })
    public static class CREDENTIAL extends Structure {
        public int Flags;
        public int Type;
        public WString TargetName;
        public WString Comment;
        public com.sun.jna.platform.win32.WinBase.FILETIME LastWritten;
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public Pointer Attributes;
        public WString TargetAlias;
        public WString UserName;

        public CREDENTIAL() {}
        public CREDENTIAL(Pointer p) { super(p); read(); }

        public static class ByReference extends CREDENTIAL implements Structure.ByReference {
            public ByReference() {}
            public ByReference(Pointer p) { super(p); }
        }
    }

    public interface Advapi32 extends StdCallLibrary {
        Advapi32 INSTANCE = Native.load("Advapi32", Advapi32.class);

        boolean CredWriteW(CREDENTIAL credential, int flags);

        boolean CredReadW(WString targetName, int type, int flags,
                          CREDENTIAL.ByReference[] credential);

        boolean CredDeleteW(WString targetName, int type, int flags);

        void CredFree(Pointer credential);
    }

    /**
     * Helper to build a {@link CREDENTIAL} with a UTF-16LE password blob.
     */
    static CREDENTIAL buildCredential(String targetName, String username, byte[] passwordBytes) {
        CREDENTIAL c = new CREDENTIAL();
        c.Flags = 0;
        c.Type = CRED_TYPE_GENERIC;
        c.TargetName = new WString(targetName);
        c.UserName = new WString(username);
        c.Persist = CRED_PERSIST_LOCAL_MACHINE;
        c.AttributeCount = 0;
        c.Attributes = null;
        c.Comment = null;
        c.TargetAlias = null;
        if (passwordBytes != null && passwordBytes.length > 0) {
            Memory mem = new Memory(passwordBytes.length);
            mem.write(0, passwordBytes, 0, passwordBytes.length);
            c.CredentialBlob = mem;
            c.CredentialBlobSize = passwordBytes.length;
        } else {
            c.CredentialBlob = Pointer.NULL;
            c.CredentialBlobSize = 0;
        }
        return c;
    }
}
