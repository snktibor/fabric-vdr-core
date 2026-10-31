package com.example.vdr.ledger;
import com.example.vdr.model.*;
public class RevRegEntryRegistry {
public Object[] answers;
//@ public ghost \seq reads;
/*@ public normal_behavior
 @ requires reads.length < answers.length;
 @ ensures \result == (Object)answers[\old(reads.length)];
 @ ensures reads == \seq_concat(\old(reads),\seq_singleton(id));
 @ ensures \result == null || (\exists Object allocated; allocated == \result);
 @ ensures \result == null || (\result instanceof RevRegEntryRecord ==> ((RevRegEntryRecord)\result).revRegDefId == \old(((RevRegEntryRecord)answers[reads.length]).revRegDefId));
 @ ensures \result == null || (\result instanceof RevRegEntryRecord ==> ((RevRegEntryRecord)\result).accumValue == \old(((RevRegEntryRecord)answers[reads.length]).accumValue));
 @ ensures \result == null || (\result instanceof RevRegEntryRecord ==> ((RevRegEntryRecord)\result).version == \old(((RevRegEntryRecord)answers[reads.length]).version));
 @ ensures \result == null || (\result instanceof RevRegEntryRecord ==> ((RevRegEntryRecord)\result).createdAt == \old(((RevRegEntryRecord)answers[reads.length]).createdAt));
 @ ensures \result == null || (\result instanceof RevRegEntryRecord ==> ((RevRegEntryRecord)\result).updatedAt == \old(((RevRegEntryRecord)answers[reads.length]).updatedAt));
 @ assignable reads;
 @*/
public /*@ helper @*/ native /*@ nullable @*/ Object get(String id);
//@ public ghost boolean present;
//@ public ghost \seq existsReads;
//@ public ghost \bigint writeCount;
//@ public ghost nullable String putKey;
//@ public ghost nullable String putId;
//@ public ghost nullable String putAccum;
//@ public ghost nullable String putCreated;
//@ public ghost nullable String putUpdated;
//@ public ghost nullable Object putRecord;
//@ public ghost long putVersion;
/*@ public normal_behavior
 @ ensures \result == present;
 @ ensures existsReads == \seq_concat(\old(existsReads),\seq_singleton(id));
 @ assignable existsReads;
 @*/
public /*@ helper @*/ native boolean exists(String id);
/*@ public normal_behavior
 @ requires record != null && \typeof(record) == \type(RevRegEntryRecord);
 @ ensures writeCount == \old(writeCount)+1 && putKey == id && putRecord == record;
 @ ensures putId == ((RevRegEntryRecord)record).revRegDefId;
 @ ensures putAccum == ((RevRegEntryRecord)record).accumValue;
 @ ensures putVersion == ((RevRegEntryRecord)record).version;
 @ ensures putCreated == ((RevRegEntryRecord)record).createdAt;
 @ ensures putUpdated == ((RevRegEntryRecord)record).updatedAt;
 @ assignable writeCount, putKey, putRecord, putId, putAccum, putVersion, putCreated, putUpdated;
 @*/
public /*@ helper @*/ native void put(String id, Object record);
}
