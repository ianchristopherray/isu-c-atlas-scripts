package com.iastate.atlas.scripts;

import static com.ensoftcorp.atlas.java.core.script.Common.edges;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import atlas.c.scripts.Queries;

import com.ensoftcorp.atlas.core.db.graph.Graph;
import com.ensoftcorp.atlas.core.db.graph.GraphElement;
import com.ensoftcorp.atlas.core.db.graph.GraphElement.EdgeDirection;
import com.ensoftcorp.atlas.core.db.graph.GraphElement.NodeDirection;
import com.ensoftcorp.atlas.core.db.set.AtlasHashSet;
import com.ensoftcorp.atlas.core.db.set.AtlasSet;
import com.ensoftcorp.atlas.core.xcsg.XCSG;
import com.ensoftcorp.atlas.java.core.highlight.Highlighter;
import com.ensoftcorp.atlas.java.core.query.Q;
import com.ensoftcorp.atlas.java.core.script.Common;
import com.ensoftcorp.atlas.ui.viewer.graph.DisplayUtil;
import com.iastate.verifier.internal.Utils;

public class FeasibilityChecker {

	private ArrayList<ArrayList<GraphElement>> paths = new ArrayList<ArrayList<GraphElement>>();
	private HashMap<GraphElement, String> nodeConstraintMap = new HashMap<GraphElement, String>();
	
	public void processCFG(String function){
		Utils.debug(0, "Started!");
		
		Q CFG = CFG(function);
		Graph cfg = CFG.eval();
		DisplayUtil.displayGraph(Common.extend(CFG, XCSG.Contains).eval(), new Highlighter(), "CFG-" + function);
		
		GraphElement entryNode = cfg.nodes().taggedWithAll(XCSG.controlFlowRoot).getFirst();
		traverse(cfg, entryNode, new ArrayList<GraphElement>());
		
		processConstraints(cfg);
		
		Utils.debug(0, "There are [" + paths.size() + "] paths!");
//		for(ArrayList<GraphElement> path : paths){
//			String p = "";
//			for(GraphElement point : path){
//				p += "[" + point.attr().get(XCSG.name) + "]  >>>  ";
//			}
//			Utils.debug(0, p);
//		}
		
		for(GraphElement node : cfg.nodes()){
			Utils.debug(0, "["+((String)node.attr().get(XCSG.name))+"] has the following constraint [" + nodeConstraintMap.get(node) + "]");
		}
		Utils.debug(0, "DONE!");
	}
	
	private void traverse(Graph graph, GraphElement currentNode, ArrayList<GraphElement> path){
		path.add(currentNode);
		
		AtlasSet<GraphElement> children = getChildNodes(graph, currentNode);
		if(children.size() > 1){
			//path.add(currentNode);
			for(GraphElement child : children){
				ArrayList<GraphElement> newPath = new ArrayList<GraphElement>(path);
				traverse(graph, child, newPath);
			}
		}else if(children.size() == 1){
			traverse(graph, children.getFirst(), path);
		}else if(children.isEmpty()){
			paths.add(path);
		}
	}
	
	private void processConstraints(Graph graph){
		for(GraphElement node : graph.nodes()){
			ArrayList<ArrayList<GraphElement>> pathsContainingNode = getPathsContainingNode(node);
						
			HashSet<String> constraints = new HashSet<String>();
			for(ArrayList<GraphElement> path : pathsContainingNode){
				constraints.add(getConstraintsFromPath(graph, path, node));
			}
			
			StringBuilder constraint = new StringBuilder();
			int i = 0;
			for(String c : constraints){
				constraint.append(c);
				if(i < constraints.size() - 1){
					constraint.append("||");
				}
				i++;
			}
			if(constraint.length() != 0){
				constraint.insert(0, "[");
				constraint.append("]");
			}
			nodeConstraintMap.put(node, constraint.toString());
		}
	}

	private ArrayList<ArrayList<GraphElement>> getPathsContainingNode(GraphElement node){
		ArrayList<ArrayList<GraphElement>> result = new ArrayList<ArrayList<GraphElement>>();
		for(ArrayList<GraphElement> path : paths){
			if(path.contains(node)){
				result.add(path);
			}
		}
		return result;
	}
	
	
	private String getConstraintsFromPath(Graph graph, ArrayList<GraphElement> path, GraphElement node){
		HashSet<String> constraints = new HashSet<String>();
		int count = -1;
		for(GraphElement element : path){
			++count;
			if(element.equals(node))
				break;
			if(element.tags().contains(XCSG.ControlFlowCondition)){
				GraphElement nextNode = path.get(count + 1);
				GraphElement edge = Utils.findEdge(graph, element, nextNode);
				String conditionValue = (String)edge.attr().get(XCSG.conditionValue);
				if(conditionValue.toLowerCase().equals("false")){
					constraints.add("F[" + element.attr().get(XCSG.name) + "]");
				}else if(conditionValue.toLowerCase().equals("true")){
					constraints.add("T[" + element.attr().get(XCSG.name) + "]");
				}else{
					//TODO: Handle switch cases and other control flow conditions that have more than 2 branches
					Utils.error(0, "Cannot know the exact condition value for [" + element.attr().get(XCSG.name) + "]");
				}
			}
		}
		StringBuilder constraint = new StringBuilder();
		int i = 0;
		for(String c : constraints){
			constraint.append(c);
			if(i < constraints.size() - 1){
				constraint.append("&&");
			}
			i++;
		}
		
		if(constraint.length() != 0){
			constraint.insert(0, "[");
			constraint.append("]");
		}
		return constraint.toString();
	}
	
	private AtlasSet<GraphElement> getChildNodes(Graph graph, GraphElement node){
		AtlasSet<GraphElement> edges = graph.edges(node, NodeDirection.OUT);
		AtlasSet<GraphElement> backEdges = edges.taggedWithAll(XCSG.ControlFlowBackEdge);
		AtlasSet<GraphElement> childNodes = new AtlasHashSet<GraphElement>();
		
		for(GraphElement edge : edges){
			if(backEdges.contains(edge))
				continue;
			GraphElement child = edge.getNode(EdgeDirection.TO);
			childNodes.add(child);
		}
		return childNodes.taggedWithAll(XCSG.ControlFlow_Node);
	}
	
	private AtlasSet<GraphElement> getParentNodes(Graph graph, GraphElement node){
		AtlasSet<GraphElement> edges = graph.edges(node, NodeDirection.IN);
		AtlasSet<GraphElement> parentNodes = new AtlasHashSet<GraphElement>();
		
		for(GraphElement edge : edges){
			GraphElement parent = edge.getNode(EdgeDirection.FROM);
			parentNodes.add(parent);
		}
		return parentNodes.taggedWithAll(XCSG.ControlFlow_Node);
	}
	
	private Q CFG(String name){
		Q method = new Queries().function(name);
		Q edges = Common.universe().edgesTaggedWithAny(XCSG.ControlFlow_Edge).differenceEdges(Common.universe().edgesTaggedWithAny(XCSG.ControlFlowBackEdge));
		Q cfg = edges(XCSG.Contains).forward(method).nodesTaggedWithAny(XCSG.ControlFlow_Node).induce(edges);
		return cfg;
	}
}
