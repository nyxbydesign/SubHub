package com.subhub.app.remote;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;

/** Seeds pre-ledger state in owned test databases for migration and recovery cases. */
final class RetainedPairingFixture {
    private RetainedPairingFixture() { }

    static void invitation(RemoteStore store, PairingInvite invite, String candidate,
            String name, String route, boolean approved) {
        store.authorityTransition("fixture-retained-invitation", invite.id, db -> {
            db.execSQL("INSERT INTO invites(id,token_hash,expires,candidate) VALUES(?,?,?,?)",
                    new Object[]{invite.id, invite.tokenHash(), invite.expiresAt, candidate});
            return null;
        });
        if (route != null) {
            store.describeCandidate(invite.id, candidate, name, route);
        }
        if (approved) {
            store.authorityTransition("fixture-retained-approval", invite.id, db -> {
                pendingPeer(db, candidate, invite.id, name, RemoteProtocol.Role.DOM, "");
                db.execSQL("UPDATE invites SET approved=1,consumed=1 WHERE id=?", new Object[]{invite.id});
                return null;
            });
        }
    }

    static void pendingSub(RemoteStore store, PairingInvite invite, String name) {
        store.authorityTransition("fixture-retained-acceptance", invite.id, db -> {
            pendingPeer(db, invite.domIdentity, invite.id, name, RemoteProtocol.Role.SUB, invite.route);
            return null;
        });
    }

    static void activateRetainedPeer(RemoteStore store, String peer, String generation) {
        store.authorityTransition("fixture-retained-activation", peer + ":" + generation, db -> {
            ContentValues values = new ContentValues();
            values.put("state", "ACTIVE");
            if (db.update("peers", values, "peer=? AND generation=? AND state='PENDING'",
                    new String[]{peer, generation}) != 1) {
                throw new AssertionError("Retained peer is not pending");
            }
            FormalRelationshipStore.mapPeer(db, peer, generation, false);
            return null;
        });
    }

    private static void pendingPeer(SQLiteDatabase db, String peer, String generation, String name,
            RemoteProtocol.Role role, String route) {
        db.execSQL("INSERT INTO peers(peer,generation,name,local_role,state,route) VALUES(?,?,?,?,'PENDING',?)",
                new Object[]{peer, generation, name, role.name(), route});
        FormalRelationshipStore.mapPeer(db, peer, generation, true);
    }
}
