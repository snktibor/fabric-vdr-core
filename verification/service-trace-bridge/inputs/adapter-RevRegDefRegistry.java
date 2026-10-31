package com.example.vdr.ledger;
import com.example.vdr.model.*;
public class RevRegDefRegistry {
public Object[] answers;
//@ public ghost \seq reads;
/*@ public normal_behavior
 @ requires reads.length < answers.length;
 @ ensures \result == (Object)answers[\old(reads.length)];
 @ ensures reads == \seq_concat(\old(reads),\seq_singleton(id));
 @ ensures \result == null || (\exists Object allocated; allocated == \result);
 @ ensures \result == null || (\result instanceof RevRegDefRecord ==> ((RevRegDefRecord)\result).ownerId == \old(((RevRegDefRecord)answers[reads.length]).ownerId));
 @ assignable reads;
 @*/
public /*@ helper @*/ native /*@ nullable @*/ Object get(String id);
}
