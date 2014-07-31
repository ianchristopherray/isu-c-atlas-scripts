package com.iastate.verifier.internal;

import static com.ensoftcorp.atlas.java.core.script.Common.universe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import atlas.c.scripts.Queries;

import com.ensoftcorp.atlas.c.core.query.Attr.Node;
import com.ensoftcorp.atlas.core.db.graph.Graph;
import com.ensoftcorp.atlas.core.db.graph.GraphElement;
import com.ensoftcorp.atlas.core.db.set.AtlasHashSet;
import com.ensoftcorp.atlas.core.db.set.AtlasSet;
import com.ensoftcorp.atlas.core.xcsg.XCSG;
import com.ensoftcorp.atlas.java.core.query.Q;
import com.ensoftcorp.atlas.java.core.script.Common;
import com.iastate.atlas.scripts.LinuxScripts;

public class CFGProcessor {

	private HashMap<GraphElement, HashMap<String, Object>> calledFunctionsSummary;
	
	private HashMap<String, Object> functionSummary;
	
	private Graph flowGraph;
	
	private AtlasSet<GraphElement> lEventNodes;
	
	private AtlasSet<GraphElement> conditionalLEventNodes;
	
	private AtlasSet<GraphElement> uEventNodes;
	
	private HashMap<GraphElement, Integer> pathToS;
	private HashMap<GraphElement, Integer> pathBackS;
	private HashMap<GraphElement, AtlasSet<GraphElement>> pathToL;
	private HashMap<GraphElement, AtlasSet<GraphElement>> pathBackL;
    
    private AtlasSet<GraphElement> matchedLNodes;
    
    private AtlasSet<GraphElement> notMatchedLNodes;
    
    private AtlasSet<GraphElement> remainingLNodes;
    
    private AtlasSet<GraphElement> remainingUNodes;
    
    boolean enableRemainingLNodes;
    
    private HashMap<GraphElement, AtlasSet<GraphElement>> matchedNodesMap;
    
    private Stater stater;
    
    private GraphElement currentFunction;
	
	public CFGProcessor(GraphElement function, Graph flowGraph, HashMap<GraphElement, HashMap<String, Object>> summary, Stater stater) {
		this.currentFunction = function;
		this.calledFunctionsSummary = (HashMap<GraphElement, HashMap<String, Object>>) summary.clone();
		this.flowGraph = flowGraph;
		
        this.pathToS = new HashMap<GraphElement, Integer>();
        this.pathToL = new HashMap<GraphElement, AtlasSet<GraphElement>>();
        
        this.pathBackS = new HashMap<GraphElement, Integer>();
        this.pathBackL = new HashMap<GraphElement, AtlasSet<GraphElement>>();
        
        this.matchedLNodes = new AtlasHashSet<GraphElement>();
        this.notMatchedLNodes = new AtlasHashSet<GraphElement>();
        this.remainingLNodes = new AtlasHashSet<GraphElement>();
        this.remainingUNodes = new AtlasHashSet<GraphElement>();
        
        this.enableRemainingLNodes = true;
        this.matchedNodesMap = new HashMap<GraphElement, AtlasSet<GraphElement>>();
        this.stater = stater;
	}
	
	public Graph run(List<Q> events){
		
		this.lEventNodes = events.get(0).eval().nodes();
		this.uEventNodes = events.get(1).eval().nodes();
		
		HashMap<GraphElement, HashMap<String, Object>> summary = new HashMap<GraphElement, HashMap<String,Object>>();
		AtlasSet<GraphElement> nodes = events.get(2).eval().nodes();
		for(GraphElement node : nodes){
			for(GraphElement calledFunction : this.calledFunctionsSummary.keySet()){
				Q callSitesQuery = universe().edgesTaggedWithAll(XCSG.Contains).forward(Common.toQ(Common.toGraph(node))).nodesTaggedWithAll(XCSG.CallSite);
				String calledFunctionName = (String) calledFunction.attr().get(XCSG.name);
				AtlasSet<GraphElement> callSites = callSitesQuery.eval().nodes();
				for(GraphElement callSite : callSites){
					String name = (String) callSite.attr().get(XCSG.name);
					if(name.contains(calledFunctionName + "(")){
						summary.put(node, this.calledFunctionsSummary.get(calledFunction));
					}
				}
			}
		}
		this.calledFunctionsSummary.clear();
		this.calledFunctionsSummary = summary;
		
		
		//Graph CFGGraph = GraphUtils.copyGraph(this.flowGraph);
		
    	this.duplicateMultipleStatusFunctions();
    	
    	this.verifyCFG();
    	
        this.stater.done();
        this.stater.setlEvents(this.lEventNodes.size());
        this.stater.setuEvents(this.uEventNodes.size());
        
        //if(this.isNotBalancedInstance()){
        //	this.stater.setNotBalancedInstances(1);
        //}
    	
        return this.flowGraph;
	}
	
    @SuppressWarnings("unchecked")
	public void verifyCFG()
    {
    	GraphElement startNode = this.flowGraph.nodes().taggedWithAll(XCSG.controlFlowRoot).getFirst();
    	
    	Object [] returns = traverseFlowGraph(startNode, PathStatus.THROUGH, new AtlasHashSet<GraphElement>(), 0);
    	
    	int rets = (Integer) returns[0];
    	AtlasSet<GraphElement> retl = (AtlasSet<GraphElement>)returns[1];
    	
    	if(rets == PathStatus.UNLOCK)
    		this.remainingUNodes.addAll(retl);
    	else
    		this.remainingUNodes = new AtlasHashSet<GraphElement>();
    	
    	if(!this.enableRemainingLNodes){
    		this.notMatchedLNodes.addAll(this.remainingLNodes);
    		this.remainingLNodes = new AtlasHashSet<GraphElement>();
    	}
    	
    	GraphElement exitNode = this.flowGraph.nodes().taggedWithAll(XCSG.controlFlowExitPoint).getFirst();
    	this.functionSummary = new HashMap<String, Object>();
    	this.functionSummary.put("rets", rets);
    	this.functionSummary.put("retl", retl);
    	this.functionSummary.put("outs", this.pathToS.get(exitNode));
    	this.functionSummary.put("outl", this.pathToL.get(exitNode));
    }
    
    @SuppressWarnings("unchecked")
	public Object[] traverseFlowGraph(GraphElement node, int pathStatus, AtlasSet<GraphElement> nodeInstances, int step){
    	Utils.debug(3, "STEP[" + step + "] LABEL[" + node.attr().get(XCSG.name) + "] STATUS[" + PathStatus.PathStatusToText(pathStatus) + "] SET[" + nodeInstances + "]");
    	step++;
    	
    	int rets = 0, outs;
    	AtlasSet<GraphElement> retl = new AtlasHashSet<GraphElement>();
    	AtlasSet<GraphElement> outl = new AtlasHashSet<GraphElement>();
    	
    	int childrens, childs;
    	AtlasSet<GraphElement> childrenl = new AtlasHashSet<GraphElement>();
    	AtlasSet<GraphElement> childl = new AtlasHashSet<GraphElement>();
    	
		if(this.calledFunctionsSummary.containsKey(node)){
			HashMap<String, Object> nodeSummary = this.calledFunctionsSummary.get(node);
	        rets = (Integer) nodeSummary.get("rets");
	        retl = (AtlasSet<GraphElement>) nodeSummary.get("retl");
	        outs = (Integer) nodeSummary.get("outs");
	        outl = (AtlasSet<GraphElement>) nodeSummary.get("outl");			
		} else if(this.lEventNodes.contains(node)){
        	rets = PathStatus.LOCK;
        	retl = new AtlasHashSet<GraphElement>();
        	outs = rets;
        	outl = this.getMyCS(node);			
		} else if(this.uEventNodes.contains(node)){
        	rets = PathStatus.UNLOCK;
        	retl = this.getMyCS(node);
        	outs = rets;
        	outl = new AtlasHashSet<GraphElement>();
		}else{
        	outs = pathStatus;
        	outl = nodeInstances;
        }
        
        boolean goon = false;
        if(this.pathToS.containsKey(node)) // visited before
        {
        	if(!this.lEventNodes.contains(node) && !this.uEventNodes.contains(node) && this.calledFunctionsSummary.get(node) == null){
        		// Normal node
	            goon = false;
	            if(!Utils.isSubSet(outl, this.pathToL.get(node))){
	            	// new Lock on the path
	                goon = true;
	                this.pathToL.get(node).addAll(outl);
	            }
	            if ((outs | this.pathToS.get(node)) != this.pathToS.get(node)){
	            	//in status on the path
	                goon = true;
	                this.pathToS.put(node, outs | this.pathToS.get(node));
	            }
	            if (goon){
	            	AtlasSet<GraphElement> children = Utils.getChildNodes(this.flowGraph, node);
	                if (children.size() == 0){
	                    childrens = PathStatus.THROUGH;
	                    childrenl = new AtlasHashSet<GraphElement>();
	                    if (pathStatus != PathStatus.LOCK)
	                    	this.enableRemainingLNodes = false;
	                }else {
	                    childrens = PathStatus.UNKNOWN;
	                    childrenl = new AtlasHashSet<GraphElement>();
	                    for (GraphElement child : children){
	                    	//Object [] out = this.checkForConditionalLEvents(node, child, outs, outl);
	                    	//outs = (Integer) out[0];
	                    	//outl = (HashSet<Node>) out[1];
	                        Object [] returns = this.traverseFlowGraph(child, outs, outl, step);
	                        childs = (Integer)returns[0];
	                        childl = (AtlasSet<GraphElement>) returns[1];
	                        Utils.debug(3, "RETURN: LABEL[" + child.attr().get(XCSG.name) + "] STATUS[" + PathStatus.PathStatusToText(childs) + "] SET[" + childl + "]");
	                        childrens |= childs;
	                        childrenl.addAll(childl);
	                    }
	                }
	                this.pathBackS.put(node, childrens);
	                this.pathBackL.put(node, new AtlasHashSet<GraphElement>(childrenl));
	                return new Object []{childrens, childrenl};
	            } else {
	            	// !goon, visited before with same information
	                if (this.pathBackS.get(node) != null)
	                    return new Object []{this.pathBackS.get(node), this.pathBackL.get(node)};
	                return new Object []{PathStatus.UNKNOWN, new AtlasHashSet<GraphElement>()};
	            }
        	}else{
        		// Lock or Unlock node or special node, stop here either way
        		return new Object []{rets, retl};
        	}
        } else{
        	// First visit on this path
            this.pathToS.put(node, outs);
            this.pathToL.put(node, new AtlasHashSet<GraphElement>(outl));
            AtlasSet<GraphElement> children = Utils.getChildNodes(this.flowGraph, node);
            if (children.size() == 0){
                childrens = PathStatus.THROUGH;
                childrenl = new AtlasHashSet<GraphElement>();
                if (pathStatus != PathStatus.LOCK)
                    this.enableRemainingLNodes = false;
            } else{
                childrens = PathStatus.UNKNOWN;
                childrenl = new AtlasHashSet<GraphElement>();
                for(GraphElement child : children){
                	//Object [] out = this.checkForConditionalLEvents(node, child, outs, outl);
                	//outs = (Integer) out[0];
                	//outl = (HashSet<Node>) out[1];
                	Object [] returns = traverseFlowGraph(child, outs, outl, step);
                	childs = (Integer) returns[0];
                	childl = (AtlasSet<GraphElement>) returns[1];
                	Utils.debug(3, "RETURN: LABEL[" + child.attr().get(XCSG.name) + "] STATUS[" + PathStatus.PathStatusToText(childs) + "] SET[" + childl + "]");
                    childrens |= childs;
                    childrenl.addAll(childl);
                }
            }

            if (this.calledFunctionsSummary.get(node) != null){
            	//special node
                // outs is only PathStatus.LOCK
                if((outs & PathStatus.LOCK) != 0){
                    for (GraphElement lcs : outl){
                        if (this.matchedNodesMap.get(lcs) == null)
                            this.matchedNodesMap.put(lcs, new AtlasHashSet<GraphElement>());
                        this.matchedNodesMap.get(lcs).addAll(childrenl);
                    }
                }
                if (outs == PathStatus.LOCK){
                    if (childrens == PathStatus.UNLOCK)
                        this.matchedLNodes.addAll(outl);
                    if (childrens == PathStatus.THROUGH)
                        this.remainingLNodes.addAll(outl);
                }else
                    this.notMatchedLNodes.addAll(outl);
            }else if(this.lEventNodes.contains(node)){
                if (childrenl.size() != 0){
                    for (GraphElement lcs : outl){
                        if (this.matchedNodesMap.get(lcs) == null)
                            this.matchedNodesMap.put(lcs, new AtlasHashSet<GraphElement>());
                        this.matchedNodesMap.get(lcs).addAll(childrenl);
                    }
                }
                if (childrens == PathStatus.UNLOCK)// Correct matching on all children
                    this.matchedLNodes.addAll(outl);
                else if(childrens == PathStatus.THROUGH) // passed to next function
                    this.remainingLNodes.addAll(outl);
                else // other non-clean cases
                    this.notMatchedLNodes.addAll(outl);
            }else if(!this.uEventNodes.contains(node)){
                rets = childrens;
                retl = new AtlasHashSet<GraphElement>(childrenl);	
            }
            this.pathBackS.put(node, rets);
            this.pathBackL.put(node, new AtlasHashSet<GraphElement>(retl));
            return new Object [] {rets, retl};
        }
    }
    
    public AtlasSet<GraphElement> getMyCS(GraphElement node)
    {
    	AtlasSet<GraphElement> nodecs = new AtlasHashSet<GraphElement>();
    	if(this.calledFunctionsSummary.get(node) == null)
    		nodecs.add(node);
    	return nodecs;
    }
	
	public HashMap<String, Object> getFunctionSummary(){
		return this.functionSummary;
	}
	
    
    /**
     * Duplicate a node in the CFG if its a called function with a summary that contains multiple statuses such as: locked and unlocked. 
     */
    public void duplicateMultipleStatusFunctions()
    {
    	for(GraphElement functionNode : this.calledFunctionsSummary.keySet()){
    		
    		if(this.calledFunctionsSummary.get(functionNode) == null)
    			this.calledFunctionsSummary.put(functionNode, new HashMap<String, Object>());
    		
    		int status = (Integer) this.calledFunctionsSummary.get(functionNode).get("rets");
    		
    		if((status | PathStatus.THROUGH) == status){
	            this.calledFunctionsSummary.get(functionNode).put("rets", 
	            		(Integer) this.calledFunctionsSummary.get(functionNode).get("rets") & ~PathStatus.THROUGH);
	            this.calledFunctionsSummary.get(functionNode).put("outs", 
	            		(Integer) this.calledFunctionsSummary.get(functionNode).get("outs") & ~PathStatus.THROUGH);
	            this.duplicateNode(functionNode);
    		}
    	}
    }
    
    public void duplicateNode(GraphElement node)
    {
    	GraphElement newNode = Graph.U.createNode(node.address());
    	newNode.tags().add(LinuxScripts.DUPLICATE_NODE);
    	
    	for(String attr : node.attr().keys()){
    		newNode.attr().put(attr, node.attr().get(attr));
    	}
    	
    	for(String tag : node.tags()){
    		newNode.tags().add(tag);
    	}
    	
    	//this.flowGraph.nodes().add(newNode);
        
        for(GraphElement child : Utils.getChildNodes(this.flowGraph, node)){
        	GraphElement currentEdge = Utils.findEdge(this.flowGraph, node, child);
        	Utils.createEdge(currentEdge, newNode, child);
        	//this.flowGraph.edges().add(Utils.createEdge(currentEdge, newNode, child));
        }
        
        for(GraphElement parent : Utils.getParentNodes(this.flowGraph, node)){
        	GraphElement currentEdge = Utils.findEdge(this.flowGraph, parent, node);
        	Utils.createEdge(currentEdge, parent, newNode);
        	//this.flowGraph.edges().add(Utils.createEdge(currentEdge, parent, newNode));
        }
        this.flowGraph = universe().edgesTaggedWithAny(XCSG.Contains, Queries.EVENT_FLOW_EDGE).forward(Common.toQ(Common.toGraph(this.currentFunction))).nodesTaggedWithAny(Queries.EVENT_FLOW_NODE).induce(universe().edgesTaggedWithAll(Queries.EVENT_FLOW_EDGE)).eval();
    }
    
    public AtlasSet<GraphElement> getLEventNodes(){
    	return this.lEventNodes;
    }

	public AtlasSet<GraphElement> getMatchedLNodes() {
		return this.matchedLNodes;
	}
	
	public AtlasSet<GraphElement> getNotMatchedLNodes() {
		return this.notMatchedLNodes;
	}
	
	public HashMap<GraphElement, AtlasSet<GraphElement>> getMatchingNodesMap(){
		return this.matchedNodesMap;
	}
	
	private boolean isNotBalancedInstance(){
		List<Integer> lLines = new ArrayList<Integer>();
		for(GraphElement node : this.lEventNodes){
			lLines.add(Integer.parseInt(node.attr().get(XCSG.sourceCorrespondence).toString()));
		}
		Collections.sort(lLines);
		
		List<Integer> uLines = new ArrayList<Integer>();
		for(GraphElement node : this.uEventNodes){
			uLines.add(Integer.parseInt(node.attr().get(XCSG.sourceCorrespondence).toString()));
		}
		Collections.sort(uLines);
		
		if(!lLines.isEmpty() && !uLines.isEmpty()){
			if(uLines.get(uLines.size() - 1) < lLines.get(0)){
				return true;
			}
		}
		return false;
	}
}
