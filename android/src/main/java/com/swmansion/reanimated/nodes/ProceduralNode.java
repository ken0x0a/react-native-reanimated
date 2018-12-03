package com.swmansion.reanimated.nodes;

import android.util.SparseArray;

import com.facebook.react.bridge.ReadableMap;
import com.swmansion.reanimated.EvalContext;
import com.swmansion.reanimated.NodesManager;
import com.swmansion.reanimated.Utils;

/**
 * ProceduralNode and related nodes are necessary in order to handle
 * context switch during evaluation
 *
 * ProceduralNode stores all results of evaluation in each contexts
 */
public class ProceduralNode extends Node {
  static private class BidirectionalContextNodeMap {
    private final SparseArray<Node> mNodesByContext = new SparseArray<>();
    private final SparseArray<EvalContext> mContextsByNode = new SparseArray<>();

    private EvalContext getContext(Node node) {
      return mContextsByNode.get(node.mNodeID);
    }

    private Node getNode(EvalContext context) {
      return mNodesByContext.get(context.contextID);
    }

    private void dropByContext(EvalContext context) {
      Node relatedNode = mNodesByContext.get(context.contextID);
      mContextsByNode.remove(relatedNode.mNodeID);
      mNodesByContext.remove(context.contextID);
    }

    private void put(EvalContext context, Node node) {
      mNodesByContext.put(context.contextID, node);
      mContextsByNode.put(node.mNodeID, context);
    }
  }

  /**
   * PerformNode provides the result of evaluation in new context to previous.
   * E.g. if procedural node has been defined in global context, the evaluation of nodes are
   * performed in new context, but the result is visible in global context because of this node
   */
  static public class PerformNode extends Node {
    private final int mProceduralNode;
    private final int[] mArgumentsInputs;
    private EvalContext mPreviousContext = null;
    private final EvalContext mEvalContext = new EvalContext(this);

    public PerformNode(int nodeID, ReadableMap config, NodesManager nodesManager) {
      super(nodeID, config, nodesManager);

      mProceduralNode = config.getInt("proceduralNode");
      mArgumentsInputs = Utils.processIntArray(config.getArray("args"));
    }

    @Override
    public void onDrop(){
      if (!mNodesManager.isNodeCreated(mProceduralNode)) {
        return;
      }
      ProceduralNode proceduralNode = mNodesManager.findNodeById(mProceduralNode, ProceduralNode.class);
      if (mPreviousContext != null) {
        for (int i = 0; i < mArgumentsInputs.length; i++) {
          if (mNodesManager.isNodeCreated(proceduralNode.mProceduralArguments[i])) {
            ArgumentNode arg = mNodesManager.findNodeById(proceduralNode.mProceduralArguments[i], ArgumentNode.class);
            arg.dropContext(mEvalContext);
          }
        }
      }
    }

    @Override
    protected Object evaluate(EvalContext previousEvalContext) {
      if (mPreviousContext == null) {
        ProceduralNode proceduralNode =  mNodesManager.findNodeById(mProceduralNode, ProceduralNode.class);
        mPreviousContext = previousEvalContext;
        for (int i = 0; i < mArgumentsInputs.length; i++) {
          int argumentID = proceduralNode.mProceduralArguments[i];
          ArgumentNode arg = mNodesManager.findNodeById(argumentID, ArgumentNode.class);
          Node inputNode = mNodesManager.findNodeById(mArgumentsInputs[i], Node.class);
          arg.matchContextWithNode(
                  mEvalContext,
                  inputNode
          );
          arg.matchNodeWithOldContext(
                  inputNode,
                  previousEvalContext
          );
        }
      } else if (mPreviousContext != previousEvalContext) {
        throw new IllegalArgumentException("Tried to evaluate perform node in more than one contexts");
      }

      ProceduralNode proceduralNode = mNodesManager.findNodeById(mProceduralNode, ProceduralNode.class);
      return proceduralNode.value(mEvalContext);
    }
  }

  /**
   * ArgumentNode has been made in order to manage input of ProceduralNode.
   * Arguments are defined in previous context but has to be accessible in context
   * of procedural node related
   */
  static public class ArgumentNode extends ValueNode {
    private final BidirectionalContextNodeMap mNodeContextMap = new BidirectionalContextNodeMap();
    private final SparseArray<EvalContext> mOldContextByNode = new SparseArray<>();

    public ArgumentNode(int nodeID, ReadableMap config, NodesManager nodesManager) {
      super(nodeID, config, nodesManager);
    }

    public void matchContextWithNode(EvalContext context, Node node) {
      mNodeContextMap.put(context, node);
    }

    public void dropContext(EvalContext evalContext) {
        mOldContextByNode.remove(mNodeContextMap.getNode(evalContext).mNodeID);

      mNodeContextMap.dropByContext(evalContext);
    }

    public void matchNodeWithOldContext(Node node, EvalContext evalContext) {
      mOldContextByNode.put(node.mNodeID, evalContext);
    }

    @Override
    public EvalContext contextForUpdatingChildren(EvalContext evalContext, Node lastVisited) {
      if (lastVisited == null) {
        return evalContext;
      }
      return mNodeContextMap.getContext(lastVisited);
    }

    /**
     * If input node is a value, there's need to allow setting value from new context.
     * Because every ValueNodes are defined in global context, their value is set in global context
     */
    @Override
    public void setValue(Object value, EvalContext context) {
      ((ValueNode)mNodeContextMap.getNode(context)).setValue(value, mNodesManager.globalEvalContext);
    }

    @Override
    protected Object evaluate(EvalContext evalContext) {
      if (evalContext == mNodesManager.globalEvalContext) {
        throw new IllegalArgumentException("Tried to evaluate argumentNode in global context");
      }
      Node value = mNodeContextMap.getNode(evalContext);
      return value.value(mOldContextByNode.get(value.mNodeID));
    }
  }

  private final int mResultNode;
  private final int[] mProceduralArguments;

  public ProceduralNode(int nodeID, ReadableMap config, NodesManager nodesManager) {
    super(nodeID, config, nodesManager);
    mResultNode = config.getInt("result");
    mProceduralArguments = Utils.processIntArray(config.getArray("arguments"));
  }

  @Override
  protected Object evaluate(EvalContext evalContext) {
    return mNodesManager.findNodeById(mResultNode, Node.class).value(evalContext);
  }
}