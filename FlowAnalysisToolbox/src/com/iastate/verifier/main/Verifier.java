package com.iastate.verifier.main;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import com.ensoftcorp.atlas.core.db.graph.Graph;
import com.ensoftcorp.atlas.core.db.graph.GraphElement;
import com.ensoftcorp.atlas.core.db.set.AtlasHashSet;
import com.ensoftcorp.atlas.core.db.set.AtlasSet;
import com.ensoftcorp.atlas.core.xcsg.XCSG;
import com.ensoftcorp.atlas.java.core.query.Q;
import com.iastate.verifier.internal.CFGProcessor;
import com.iastate.verifier.internal.PathStatus;
import com.iastate.verifier.internal.Stater;
import com.iastate.verifier.internal.Utils;

public class Verifier {
	
	private String signature;
	
	private Graph envelope;
	
	private Stater stater;
	
	private HashMap<GraphElement, AtlasSet<GraphElement>> matchedNodesMap;
	
	private AtlasSet<GraphElement> problematicLEvents;
	
	private HashMap<GraphElement, Graph> functionEFGMap;
	private HashMap<GraphElement, List<Q>> functionEventsMap;
	
	/**
	 * A HashMap to store each function's summaries based on the analyzed property
	 */
	private HashMap<GraphElement, HashMap<String, Object>> functionSummariesMap;
	
	public Verifier(String signature, Graph callGraph, HashMap<GraphElement, Graph> functionEFGMap, HashMap<GraphElement, List<Q>> functionEventsMap){
		this.signature = signature;
		this.envelope = callGraph;
		this.stater = new Stater();
		this.functionSummariesMap = new HashMap<GraphElement, HashMap<String,Object>>();
		this.matchedNodesMap = new HashMap<GraphElement, AtlasSet<GraphElement>>(); 
		this.problematicLEvents = new AtlasHashSet<GraphElement>();
		this.functionEFGMap = functionEFGMap;
		this.functionEventsMap = functionEventsMap;
	}
	
	@SuppressWarnings("unchecked")
	public void run(){
		if(!Utils.isDirectedAcyclicGraph(this.envelope)){
			Utils.error(0, "The call graph envelope is a cyclic graph!");
			System.exit(-2);
		}
		
		Utils.debug(1, "The call graph envelope has ["+ this.envelope.nodes().size() +"] nodes.");
		
		// Topology sort of the MPG -> list T, not for cyclic graph yet
		List<GraphElement> functions = Utils.topologicalSort(this.envelope);
		Collections.reverse(functions);
		
		for(GraphElement function : functions){
			Utils.debug(2, "--------Function:" + function.attr().get(XCSG.name));
			Utils.debug(2, "Outdegree:" + Utils.getChildNodes(this.envelope, function).size());
			processFunction(function);
		}
		
		AtlasSet<GraphElement> danglingLNodes = new AtlasHashSet<GraphElement>();
		AtlasSet<GraphElement> notMatchedLocks = new AtlasHashSet<GraphElement>();
		
		int outStatus;
		for(GraphElement function : this.functionSummariesMap.keySet()){
			if(Utils.getParentNodes(this.envelope, function).size() == 0){
				outStatus = (Integer)this.functionSummariesMap.get(function).get("outs");
				if((outStatus & PathStatus.LOCK) != 0){
					danglingLNodes.addAll((AtlasHashSet<GraphElement>) this.functionSummariesMap.get(function).get("outl")) ;
				}
				
				if(outStatus == PathStatus.LOCK || outStatus == (PathStatus.LOCK | PathStatus.THROUGH)){
					notMatchedLocks.addAll((AtlasHashSet<GraphElement>) this.functionSummariesMap.get(function).get("outl")) ;
				}
			}
		}
		
		AtlasSet<GraphElement> partiallyMatchedNodes = Utils.difference(danglingLNodes, notMatchedLocks);
		for(GraphElement node : partiallyMatchedNodes){
			this.matchedNodesMap.remove(node);
		}
		
		// TODO: To get the real number of matched nodes, we need to subtract the number of not-verified ones
		for(GraphElement node: notMatchedLocks){
			this.matchedNodesMap.remove(node);
		}
		
		this.stater.setVerifiedLEvents(this.matchedNodesMap.size());
		this.stater.setPartiallyVerifiedLEvents(partiallyMatchedNodes.size());
		this.stater.setNotVerifiedLEvents(notMatchedLocks.size());
		setInterOrIntraproceduralCasesCount();
		
		this.stater.done();
		//logManualCheckingCases(notMatchedLocks, partiallyMatchedNodes);
		this.stater.printResults("[" + this.signature + "]");
		
		try {
			Utils.writer.flush();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	/**
	 * Process the CFG result for node (function) in MVE
	 * @param function node in MVE
	 */
	@SuppressWarnings("unchecked")
	public void processFunction(GraphElement function)
	{
		HashMap<GraphElement, HashMap<String, Object>> calledFunctionsSummary = new HashMap<GraphElement, HashMap<String,Object>>();
		
		Utils.debug(3, "Function name:" + function.attr().get(XCSG.name));
		
		AtlasSet<GraphElement> children = Utils.getChildNodes(this.envelope, function);
		for(GraphElement child : children)
			calledFunctionsSummary.put(child, (HashMap<String, Object>) this.functionSummariesMap.get(child).clone());
		
		Stater perCFGStats = new Stater();
		CFGProcessor processor = new CFGProcessor(function, this.functionEFGMap.get(function), calledFunctionsSummary, perCFGStats);
		
		processor.run(this.functionEventsMap.get(function));
		
		HashMap<String, Object> functionSummary = (HashMap<String, Object>) processor.getFunctionSummary().clone();
		this.functionSummariesMap.put(function, functionSummary);
		
		HashMap<GraphElement, AtlasSet<GraphElement>> tempMap = processor.getMatchingNodesMap();
		
		//TODO: This is a new way that need to be tested for false alarm of raced L events (The L Event forming a race condition)
		// This could be false alarm as the signatures match but the actual parameter not, can be resolved using parameter matching instead of signature matching
		//if(this.mvEnvelope.getInDegree(functionNode) == 0){
			AtlasSet<GraphElement> allLNodesInFunction = new AtlasHashSet<GraphElement>();
			for(GraphElement a : tempMap.keySet())
				allLNodesInFunction.add(a);
			
			int outStatus = (Integer)functionSummary.get("outs");
			if((outStatus & PathStatus.LOCK) != 0){
				allLNodesInFunction.addAll((AtlasHashSet<GraphElement>) functionSummary.get("outl")) ;
			}
			
			if(outStatus == PathStatus.LOCK || outStatus == (PathStatus.LOCK | PathStatus.THROUGH)){
				allLNodesInFunction.addAll((AtlasHashSet<GraphElement>) functionSummary.get("outl")) ;
			}
			AtlasSet<GraphElement> racedLEvents = Utils.difference(processor.getLEventNodes(), Utils.intersection(allLNodesInFunction, processor.getLEventNodes()));
			this.problematicLEvents.addAll(racedLEvents);
			if(!racedLEvents.isEmpty()){
				perCFGStats.setRacedLEvents(racedLEvents.size());
			}
		//}
		
		this.stater.aggregate(perCFGStats);
		
		for(GraphElement node : tempMap.keySet()){
			AtlasSet<GraphElement> uVerifiers = this.matchedNodesMap.get(node);
			if(uVerifiers == null){
				uVerifiers = new AtlasHashSet<GraphElement>();
			}
			uVerifiers.addAll(tempMap.get(node));
			this.matchedNodesMap.put(node, uVerifiers);
		}
	}
	
	private void setInterOrIntraproceduralCasesCount(){
        for(GraphElement matchedNode : this.matchedNodesMap.keySet()){
        	Graph myGraph = getMyGraphNodes(matchedNode);
        	AtlasSet<GraphElement> uNodes = this.matchedNodesMap.get(matchedNode);
        	if(Utils.isSubSet(uNodes, myGraph.nodes())){
        		// This is the case of intra-procedural verification
        		this.stater.setIntraproceduralVerification(this.stater.getIntraproceduralVerification() + 1);
        	} else{
        		// This is the case of inter-procedural verification
        		this.stater.setInterproceduralVerification(this.stater.getInterproceduralVerification() + 1);
        	}
        }
	}
	
	private Graph getMyGraphNodes(GraphElement node){
		for(Graph cfg : this.functionEFGMap.values()){
			if(cfg.nodes().contains(node))
				return cfg;
		}
		return null;
	}
	
	private void logManualCheckingCases(HashSet<GraphElement> notMatchedLocks, HashSet<GraphElement> partiallyMatchedNodes) {
		if(!(partiallyMatchedNodes.isEmpty() && notMatchedLocks.isEmpty() && this.problematicLEvents.isEmpty())){
			System.out.println("---------------------------------------------");
			System.out.println("---------------------------------------------");
			System.out.println("Signature: [" + this.signature + "]");
			System.out.println("---------------------------------------------");
			System.out.println("---------------------------------------------");
			for(GraphElement node : partiallyMatchedNodes){
				System.out.println(getDebugInformation(node, true));
			}
			System.out.println("---------------------------------------------");
			for(GraphElement node : notMatchedLocks){
				System.out.println(getDebugInformation(node, false));
			}
			System.out.println("---------------------------------------------");
			for(GraphElement node : this.problematicLEvents){
				System.out.println(getDebugInformation(node, true));
			}
		}
	}
	
	private String getDebugInformation(GraphElement node, boolean reportSourceCode){
		for(GraphElement function : this.functionEFGMap.keySet()){
			Graph cfg = this.functionEFGMap.get(function);
			if(cfg.nodes().contains(node)){
				return "MANUAL CHECK EVENT: [" + node.attr().get(XCSG.name) + "] @ Line Number [" + node.attr().get(XCSG.sourceCorrespondence) + "] in Function [" + function.attr().get(XCSG.name) + "]";
			}
		}
		return "MANUAL CHECK EVENT: Error in getting info for [" + node.address().toAddressString() + "]";		
	}

}
