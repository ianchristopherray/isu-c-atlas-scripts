package com.iastate.verifier.internal;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

import com.alexmerz.graphviz.ParseException;
import com.alexmerz.graphviz.Parser;
import com.alexmerz.graphviz.objects.Edge;
import com.alexmerz.graphviz.objects.Graph;
import com.alexmerz.graphviz.objects.Id;
import com.alexmerz.graphviz.objects.Node;
import com.alexmerz.graphviz.objects.PortNode;
import com.ensoftcorp.atlas.core.db.graph.GraphElement;
import com.ensoftcorp.atlas.core.db.graph.GraphElement.EdgeDirection;
import com.ensoftcorp.atlas.core.db.graph.GraphElement.NodeDirection;
import com.ensoftcorp.atlas.core.db.set.AtlasHashSet;
import com.ensoftcorp.atlas.core.db.set.AtlasSet;
import com.ensoftcorp.atlas.core.xcsg.XCSG;
import com.iastate.atlas.scripts.AnalysisScripts;
import com.iastate.verifier.main.Config;

public class Utils {
	
	public static void addEFGToIndex(GraphElement functionNode, com.ensoftcorp.atlas.core.db.graph.Graph cfg, Graph efg){
		HashMap<String, GraphElement> nodeAddressMap = new HashMap<String, GraphElement>();
		for(GraphElement node : cfg.nodes()){
			nodeAddressMap.put(node.address().toAddressString(), node);
		}
		
		GraphElement entryNode = com.ensoftcorp.atlas.core.db.graph.Graph.U.createNode();
		entryNode.attr().put(XCSG.name, "EFG Entry");
		entryNode.tags().add(AnalysisScripts.EVENT_FLOW_NODE);
		entryNode.tags().add(XCSG.controlFlowRoot);
		nodeAddressMap.put("cfg0", entryNode);
		GraphElement e = com.ensoftcorp.atlas.core.db.graph.Graph.U.createEdge(functionNode, entryNode);
		e.tags().add(XCSG.Contains);
		
		
		GraphElement exitNode = com.ensoftcorp.atlas.core.db.graph.Graph.U.createNode();
		exitNode.attr().put(XCSG.name, "EFG Exit");
		exitNode.tags().add(AnalysisScripts.EVENT_FLOW_NODE);
		exitNode.tags().add(XCSG.controlFlowExitPoint);
		nodeAddressMap.put("cfg1", exitNode);
		e = com.ensoftcorp.atlas.core.db.graph.Graph.U.createEdge(functionNode, exitNode);
		e.tags().add(XCSG.Contains);
		
		for(Edge edge : efg.getEdges()){
			Node fromNode = edge.getSource().getNode();
			String fromNodeAddress = fromNode.getLabel();
			GraphElement newFromNode = nodeAddressMap.get(fromNodeAddress);
			if(newFromNode != null){
				newFromNode.tags().add(AnalysisScripts.EVENT_FLOW_NODE);
			}
			
			Node toNode = edge.getTarget().getNode();
			String toNodeAddress = toNode.getLabel();
			GraphElement newToNode = nodeAddressMap.get(toNodeAddress);
			if(newToNode != null){
				newToNode.tags().add(AnalysisScripts.EVENT_FLOW_NODE);
			}
			
			if(newFromNode != null && newToNode != null){	
				GraphElement newEdge = com.ensoftcorp.atlas.core.db.graph.Graph.U.createEdge(newFromNode, newToNode);
				newEdge.tags().add(AnalysisScripts.EVENT_FLOW_EDGE);
			}
		}
	}
	
	public static Graph createEventFlowGraph(com.ensoftcorp.atlas.core.db.graph.Graph graph, AtlasSet<GraphElement> highlightedNodes){
		Graph newGraph = Utils.transformAtlasGraph(graph, highlightedNodes);
		EventFlowGraphCreator efgCreator = new EventFlowGraphCreator(newGraph);
		efgCreator.create();
		return efgCreator.getFlowGraph(); 
	}
	
	/**
	 * Transform Atlas Graph into DOT Graph
	 */
	public static Graph transformAtlasGraph(com.ensoftcorp.atlas.core.db.graph.Graph graph, AtlasSet<GraphElement> highlightedNodes){
		Graph newGraph = createNewGraph();
		
		for(GraphElement edge : graph.edges()){
			GraphElement fromNode = edge.getNode(EdgeDirection.FROM);
			Node newFromNode = createNode(newGraph, fromNode.address().toAddressString());
			newFromNode.setAttribute("label", escape(fromNode.attr().get(XCSG.name).toString()));
			
			GraphElement toNode = edge.getNode(EdgeDirection.TO);
			Node newToNode = createNode(newGraph, toNode.address().toAddressString());
			newToNode.setAttribute("label", escape(toNode.attr().get(XCSG.name).toString()));
			
			Edge newEdge = createEdge(newGraph, newFromNode, newToNode);
			if(edge.attr().containsKey("conditionValue")){
				newEdge.setAttribute("label", edge.attr().get("conditionValue").toString());
			}
			
			if(highlightedNodes.contains(fromNode)){
				newFromNode.setAttribute("style", "filled");
				newFromNode.setAttribute("fillcolor", "red");
			}
			if(highlightedNodes.contains(toNode)){
				newToNode.setAttribute("style", "filled");
				newToNode.setAttribute("fillcolor", "red");					
			}
		}
		
		Node START_NODE = createNode(newGraph, "cfg0");
		START_NODE.setAttribute("cfg_type", "START");
		
		Node EXIT_NODE = createNode(newGraph, "cfg1");
		EXIT_NODE.setAttribute("cfg_type", "END");
		
		for(GraphElement node : graph.nodes()){
			if(node.tags().containsAny(XCSG.controlFlowRoot)){
				createEdge(newGraph, START_NODE, newGraph.findNode(node.address().toAddressString()));
				continue;
			}
			
			if(node.tags().containsAny(XCSG.controlFlowExitPoint)){
				createEdge(newGraph, newGraph.findNode(node.address().toAddressString()), EXIT_NODE);
			}
		}
		
		return newGraph;
	}
	
	/**
	 * Create and adds a new node to the graph, if the node already created then return it
	 * @param graph
	 * @param label
	 * @return the created or existing node
	 */
	public static Node createNode(Graph graph, String label){
		Node newNode = graph.findNode(label);
		if(newNode != null)
			return newNode;
    	
		newNode = new Node();
    	Id id = new Id();
    	id.setLabel(label);
    	newNode.setId(id);
    	graph.addNode(newNode);
    	return newNode;
	}
	
	/**
	 * Creates and adds a new edge to the graph
	 * @param graph
	 * @param from
	 * @param to
	 * @return new created edge
	 */
	public static Edge createEdge(Graph graph, Node from, Node to){
		Edge newEdge = new Edge(new PortNode(from), new PortNode(to), graph.getType());
		graph.addEdge(newEdge);
		return newEdge;
	}
	
	public static String escape(String str){
		return str.replace("\"", "''").replace("\n", "\t");
	}
	
	/**
	 * Creates an empty jpgd graph
	 * @return
	 */
	public static Graph createNewGraph(){
		StringBuffer sb = new StringBuffer();
		sb.append("digraph G {\n");
		sb.append("\n");
		sb.append("}\n");
		
		Parser p = new Parser();
		try {
			p.parse(sb);
		} catch (ParseException e) {
			e.printStackTrace();
		}
		List<Graph> graphs = p.getGraphs();
		if(graphs.size() != 1){
			return null;
		}
		return graphs.get(0);		
	}

	/**
	 * Get from a graph an entrance of exit node
	 * @param isEntry: true to return an entry node, otherwise, and exit node returned
	 * @return entry/exit node
	 */
	public static Node getEntryExitNodes(Graph graph, boolean isEntry)
    {
    	String label = isEntry ? "START" : "END";
    	List<Node> nodes = new ArrayList<Node>();
    	String value = "";
    	for(Node node : graph.getNodes()){
    		value = node.getAttribute("cfg_type");
    		if(value != null && value.equals(label))
    			nodes.add(node);
    	}
    	int size = nodes.size();
    	if(size == 0 | size != 1){
			System.err.println("No/Multiple Entries/Exits(" + size + ")!");
    	}
    	return nodes.get(0);
    }
	
	
	public static Graph copyGraph(Graph g){
		StringBuffer sb = new StringBuffer();
		sb.append(g.toString().replace("\\\"", "").replace("\\", "/").replace(" }", "}"));
		
		Parser p = new Parser();
		try {
			p.parse(sb);
		} catch (ParseException e) {
			e.printStackTrace();
		}
		List<Graph> graphs = p.getGraphs();
		if(graphs.size() != 1){
			return null;
		}
		return graphs.get(0);	
	}
	
	public static boolean isDirectedAcyclicGraph(com.ensoftcorp.atlas.core.db.graph.Graph graph) 
	{
		return (topologicalSort(graph) != null);
	}

	/**
	 * Return a list of nodes in topological sort order
	 * A topological sort is a non-unique permutation of the nodes such that an edge from u to v implies that u appears before v in the topological sort order
	 * The topological sort is defined for directed graphs only, if the graph if undirected, the return value is null
	 * @param graph
	 * @return
	 */
	public static List<GraphElement> topologicalSort(com.ensoftcorp.atlas.core.db.graph.Graph graph){
		LinkedHashSet<GraphElement> seen = new LinkedHashSet<GraphElement>(); 
		List<GraphElement> explored = new ArrayList<GraphElement>();
		
		for(GraphElement v : graph.nodes()){
			if(!explored.contains(v)){
				if(!DFS(graph, seen, explored, v)){
					return null;
				}
			}
		}
		return explored;
	}
	
	public static boolean DFS(com.ensoftcorp.atlas.core.db.graph.Graph graph, LinkedHashSet<GraphElement> seen, List<GraphElement> explored, GraphElement v){
		seen.add(v);
		for(GraphElement node: getChildNodes(graph, v)){
			if(!seen.contains(node)){
				if(!DFS(graph, seen, explored, node))
					return false;
			}
			else if(seen.contains(node) && !explored.contains(node)){
				return false;
			}
		}
		explored.add(0, v);
		return true;
	}
	
	public static AtlasSet<GraphElement> getChildNodes(com.ensoftcorp.atlas.core.db.graph.Graph graph, GraphElement node){
		AtlasSet<GraphElement> edges = graph.edges(node, NodeDirection.OUT);
		AtlasSet<GraphElement> childNodes = new AtlasHashSet<GraphElement>();
		for(GraphElement edge : edges){
			GraphElement child = edge.getNode(EdgeDirection.TO);
			childNodes.add(child);
		}
		return childNodes;
	}
	
	public static AtlasSet<GraphElement> getParentNodes(com.ensoftcorp.atlas.core.db.graph.Graph graph, GraphElement node){
		AtlasSet<GraphElement> edges = graph.edges(node, NodeDirection.IN);
		AtlasSet<GraphElement> parentNodes = new AtlasHashSet<GraphElement>();
		for(GraphElement edge : edges){
			GraphElement parent = edge.getNode(EdgeDirection.FROM);
			parentNodes.add(parent);
		}
		return parentNodes;
	}
	
	
	public static void debug(int level, String message)
	{
		if(level <= Config.DEBUG_LEVEL)
			System.out.println("[DEBUG(" + level + ")]:" + message);
	}
	
	public static void error(int level, String message)
	{
		if(level <= Config.ERROR_LEVEL)
			System.err.println("[ERROR(" + level + ")]:" + message);		
	}
	
	public static AtlasSet<GraphElement> difference(AtlasSet<GraphElement> a, AtlasSet<GraphElement> b){
		AtlasSet<GraphElement> result =  new AtlasHashSet<GraphElement>(a);
		for(GraphElement i : b)
			result.remove(i);
		return result;
	}
	
	public static boolean isSubSet(AtlasSet<GraphElement> A, AtlasSet<GraphElement> B)
	{
		for(GraphElement a : A){
			if(!B.contains(a))
				return false;
		}
		return true;
	}
	
	public static AtlasSet<GraphElement> intersection(AtlasSet<GraphElement> a, AtlasSet<GraphElement> b){
		AtlasSet<GraphElement> result = new AtlasHashSet<GraphElement>(a);
		for(GraphElement i : b){
			if(!result.contains(i))
				result.remove(i);
		}
		return result;
	}
}
