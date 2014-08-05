package atlas.c.fat.smartview;

import com.ensoftcorp.atlas.core.xcsg.XCSG;
import com.ensoftcorp.atlas.java.core.query.Attr.Edge;
import com.ensoftcorp.atlas.java.core.query.Attr.Node;
import com.ensoftcorp.atlas.java.core.query.Q;
import com.ensoftcorp.atlas.java.core.query.Query;
import com.ensoftcorp.atlas.java.core.script.CommonQueries;
import com.ensoftcorp.atlas.java.core.script.CommonQueries.TraversalDirection;
import com.ensoftcorp.atlas.java.core.script.StyledResult;
import com.ensoftcorp.atlas.java.ui.scripts.selections.AtlasSmartViewScript;


/**
 * Does a 2 step reverse dataflow
 *
 */
public class DFReverseStep implements AtlasSmartViewScript {

	@Override
	public String[] getSupportedNodeTags() {
		return new String[]{Node.DATA_FLOW, XCSG.DataFlow_Node, Node.VARIABLE, XCSG.Variable};
	}

	@Override
	public String[] getSupportedEdgeTags() {
		return new String[]{Edge.DATA_FLOW, XCSG.DataFlow_Edge};
	}

	@Override
	public StyledResult selectionChanged(SelectionInput input) {
		Q interpretedSelection = input.getInterpretedSelection();
		
//		Q f1 = CommonQueries.dataStep(interpretedSelection, TraversalDirection.FORWARD);
//		Q f2 = CommonQueries.dataStep(f1, TraversalDirection.FORWARD);
		
		Q context = Query.universe().edgesTaggedWithAll(XCSG.DataFlow_Edge, com.ensoftcorp.atlas.c.core.query.Attr.Edge.ADDRESS_OF, com.ensoftcorp.atlas.c.core.query.Attr.Edge.POINTER_DEREFERENCE);
		Q r1 = context.reverseStep(interpretedSelection);
		Q r2 = context.reverseStep(r1);
		
//		Q res = f2.union(r2);
		Q res = r2;
		
		return new StyledResult(res);
	}

	@Override
	public String getTitle() {
		return "Data Flow, Reverse Step";
	}
}