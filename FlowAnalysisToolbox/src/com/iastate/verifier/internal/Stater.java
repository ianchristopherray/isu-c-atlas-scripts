package com.iastate.verifier.internal;

import java.util.Date;

public class Stater {

	private long startTime;
	
	private long lEvents;
	
	private long uEvents;
	
	/**
	 * The count of L events that are verified with U on every execution path
	 */
	private int verifiedLEvents;
	
	/**
	 * The count of L events that are verified with U on some execution paths
	 */
	private long partiallyVerifiedLEvents;
	
	/**
	 * The count of L events that are not verified with any U on every execution paths
	 */
	private long notVerifiedLEvents;
	
	/**
	 * The count of L events that are followed by another L event causing a race condition
	 */
	private long racedLEvents;
	
	private int interproceduralVerification;
	
	private int intraproceduralVerification;
	
	private int nonBalancedLEvents;
	
	public Stater() {
		this.startTime = System.currentTimeMillis();
		Utils.debug(0, "Started at [" + new Date().toString() + "]");
		
		this.lEvents = 0;
		this.uEvents = 0;
		this.verifiedLEvents = 0;
		this.partiallyVerifiedLEvents = 0;
		this.notVerifiedLEvents = 0;
		this.racedLEvents = 0;
		this.interproceduralVerification = 0;
		this.intraproceduralVerification = 0;
		this.nonBalancedLEvents = 0;
	}
	
	public void done(){
	    float end = this.getProcessingTime()/(60*1000F);
	    Utils.debug(0, "Done in [" + end + " minutes]!");
	}
	
	public void aggregate(Stater subStats){
		this.lEvents += subStats.getlEvents();
		this.uEvents += subStats.getuEvents();
		this.verifiedLEvents += subStats.getVerifiedLEvents();
		this.partiallyVerifiedLEvents += subStats.getPartiallyVerifiedLEvents();
		this.notVerifiedLEvents += subStats.getNotVerifiedLEvents();
		this.racedLEvents += subStats.getRacedLEvents();
		this.interproceduralVerification += subStats.getInterproceduralVerification();
		this.intraproceduralVerification += subStats.getIntraproceduralVerification();
		this.nonBalancedLEvents += subStats.getNotBalancedInstances();
	}
	
	public long getProcessingTime(){
		return (System.currentTimeMillis() - startTime);
	}
	
	public long getlEvents() {
		return lEvents;
	}

	public void setlEvents(long l) {
		this.lEvents = l;
	}

	public long getuEvents() {
		return uEvents;
	}

	public void setuEvents(long l) {
		this.uEvents = l;
	}

	public long getPartiallyVerifiedLEvents() {
		return partiallyVerifiedLEvents;
	}

	public void setPartiallyVerifiedLEvents(long l) {
		this.partiallyVerifiedLEvents = l;
	}
	
	public int getVerifiedLEvents() {
		return verifiedLEvents;
	}

	public void setVerifiedLEvents(int verifiedLEvents) {
		this.verifiedLEvents = verifiedLEvents;
	}

	public long getNotVerifiedLEvents() {
		return notVerifiedLEvents;
	}

	public void setNotVerifiedLEvents(long l) {
		this.notVerifiedLEvents = l;
	}

	public int getInterproceduralVerification() {
		return interproceduralVerification;
	}

	public void setInterproceduralVerification(int interproceduralVerification) {
		this.interproceduralVerification = interproceduralVerification;
	}

	public int getIntraproceduralVerification() {
		return intraproceduralVerification;
	}

	public void setIntraproceduralVerification(int intraproceduralVerification) {
		this.intraproceduralVerification = intraproceduralVerification;
	}
	
	public long getRacedLEvents() {
		return racedLEvents;
	}

	public void setRacedLEvents(long l) {
		this.racedLEvents = l;
	}
	
	public void printResults(String title){
	    Utils.debug(0, "******************************************");
	    Utils.debug(0, "*****************" + title + " STATISTICS***************");
	    Utils.debug(0, "******************************************");
	    Utils.debug(0, "Number of L Events: " + this.lEvents);
	    Utils.debug(0, "Number of U Events: " + this.uEvents);
	    double verifiedPercentage = (((double)this.verifiedLEvents) / ((double) this.lEvents)) * 100.0;
	    Utils.debug(0, "Number of Verified L Events: " + this.verifiedLEvents + "\t[" + verifiedPercentage + "%]");
	    double partiallyVerifiedPercentage = (((double)this.partiallyVerifiedLEvents) / ((double) this.lEvents)) * 100.0;
	    Utils.debug(0, "Number of Partially Verified L Events: " + this.partiallyVerifiedLEvents + "\t[" + partiallyVerifiedPercentage + "%]");
	    double notVerifiedPercentage = (((double)this.notVerifiedLEvents) / ((double) this.lEvents)) * 100.0;
	    Utils.debug(0, "Number of Not-Verified L Events: " + this.notVerifiedLEvents + "\t[" + notVerifiedPercentage + "%]");
	    double racedPercentage = (((double)this.racedLEvents) / ((double) this.lEvents)) * 100.0;
	    Utils.debug(0, "Number of Raced L Events: " + this.racedLEvents + "\t[" + racedPercentage + "%]");	    
	    Utils.debug(0, "Number of Intra-procedural Cases: " + this.intraproceduralVerification);
	    Utils.debug(0, "Number of Inter-procedural Cases: " + this.interproceduralVerification);
	    Utils.debug(0, "Number of Non-Balanced L Events: " + this.nonBalancedLEvents);
	    //if(Config.USE_CFG){
	    //	Utils.debug(0, "CFG to EFG Convertion Time: " + (Config.CFG_TO_EFG_CONVERSION_TIME /(1000F)) + " seconds!");
	    //}
	    Utils.debug(0, "******************************************");
	    Utils.debug(0, "******************************************");
	    Utils.debug(0, "******************************************");
	}

	public int getNotBalancedInstances() {
		return nonBalancedLEvents;
	}

	public void setNotBalancedInstances(int notBalancedInstances) {
		this.nonBalancedLEvents = notBalancedInstances;
	}
}
