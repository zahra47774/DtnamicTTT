package dynamicTTT;

import TTT.TTT;
import TTT.discriminatorTrie.DiscriminatorTrie;
import TTT.discriminiationTree.*;
import de.learnlib.api.query.DefaultQuery;
import dynamicTTT.discriminationTree.DynamicDiscriminationTree;
import dynamicTTT.spanningTree.OutdatedSpanningTreeContainer;
import modelLearning.MembershipCounter;
import modelLearning.ModelLearner;
import modelLearning.Teacher;
import net.automatalib.automata.fsa.DFA;
import net.automatalib.automata.fsa.impl.compact.CompactDFA;
import net.automatalib.serialization.dot.DefaultDOTVisualizationHelper;
import net.automatalib.visualization.Visualization;
import net.automatalib.words.Alphabet;
import TTT.spanningTree.SpanningTree;
import TTT.TTTNode;
import net.automatalib.words.Word;
import net.automatalib.words.WordBuilder;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.*;

public class DynamicTTT<I> extends ModelLearner<I> implements  MembershipCounter<I> {

    private final DiscriminationTree<I> outdatedDiscriminationTree;
    private final List<TTTNode<I>> tempSpanningTree = new ArrayList<>();
    private final HashMap<Word<I>, TTTNode<I>> equivalenceStateMap = new HashMap<Word<I>, TTTNode<I>>();
    private final Alphabet<I> alphabet;
    private final CompactDFA<I> hypothesis;
    private final DynamicDiscriminationTree<I> discriminationTree;
    private SpanningTree<I> spanningTree;
    private final OutdatedSpanningTreeContainer<I> outdatedPrefixesContainer;


    public DynamicTTT(Teacher<I> teacher,
                      SpanningTree<I> outdatedSpanningTree,
                      DiscriminationTree<I> outdatedDiscriminationTree,
                      Alphabet<I> updatedAlphabet) {
        super(teacher);
        this.alphabet = updatedAlphabet;
        this.hypothesis = new CompactDFA<>(this.alphabet);
        this.outdatedDiscriminationTree = outdatedDiscriminationTree;
        this.discriminationTree = new DynamicDiscriminationTree<>(this.teacher);
        this.outdatedPrefixesContainer = new OutdatedSpanningTreeContainer<>(outdatedSpanningTree, this.alphabet, this::tempSpanningTreeContain);
    }

    @Override
    public DFA<?, I> learn() {
        try {
            reconstructHypothesis();
            Visualization.visualize(hypothesis, alphabet, new DefaultDOTVisualizationHelper<>());
            completeHypothesis();
            cleanDiscriminationTree();


            TTT<I> tttLearner = new TTT<>(
                    teacher,
                    this.alphabet,
                    this.hypothesis,
                    this.spanningTree,
                    this.discriminationTree,
                    new DiscriminatorTrie<>(this.alphabet)
            );
            tttLearner.finalizeHypothesis();
            while (true) {
                @Nullable DefaultQuery<I, Boolean> eq = teacher.equivalenceQuery(hypothesis, alphabet);
                if (eq == null)
                    return tttLearner.getHypothesis();
                tttLearner.refineHypothesis(eq);
                tttLearner.stabilizeHypothesis();
                tttLearner.finalizeHypothesis();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void reconstructHypothesis() throws Exception {
        discriminationTree.initialDiscriminationTree(outdatedDiscriminationTree);

        Iterator<Word<I>> prefixIterator = outdatedPrefixesContainer.getOutdatedPrefixes();
        while (prefixIterator.hasNext()) {

            Word<I> prefix = prefixIterator.next();
            if (tempSpanningTreeContain(prefix))
                continue;
            DTLeaf<I> pos = this.discriminationTree.sift(prefix);
            if (pos instanceof EmptyDTLeaf) { // add a new state!
                TTTNode<I> node = createState(prefix, true);
                addToEquivalenceStateMap(node.sequenceAccess, node);
                tempSpanningTree.add(node);
                this.discriminationTree.discriminate(node, pos);
                outdatedPrefixesContainer.expandPrefix(node.sequenceAccess);
            } else { //update transitions
                addToEquivalenceStateMap(prefix, pos.state);
                int destStateId = pos.state.id;
                Word<I> prefixPrefix = prefix.prefix(prefix.size() - 1);
                int originId = equivalenceStateMap.get(prefixPrefix).id;
                I transition = prefix.lastSymbol();
                if (hasTransition(this.hypothesis, originId, transition))
                    hypothesis.addTransition(originId, transition, destStateId);
            }
        }
    }

    public void completeHypothesis() throws Exception {
        tempSpanningTree.sort(Comparator.comparingInt(o -> o.sequenceAccess.size()));

        for (TTTNode<I> node : tempSpanningTree) {
            Deque<TTTNode<I>> queue = new ArrayDeque<>();

            TTTNode<I> currNode = node;

            if (node.sequenceAccess.size() == 0) { //initial node
                spanningTree = new SpanningTree<>(node);
                continue;
            }

            while (true) {
                queue.addFirst(currNode);
                Word<I> sequence = currNode.sequenceAccess;
                Word<I> prefix = currNode.sequenceAccess.prefix(sequence.size() - 1);
                if (tempSpanningTreeContain(prefix))
                    break;
                currNode = discriminateLongestPrefix(currNode);
            }

            for (TTTNode<I> tttNode : queue) {
                boolean result = spanningTree.addState(tttNode);
                expandStateTransitions(tttNode);
                assert result;
            }
        }
    }


    public void cleanDiscriminationTree() {
        this.discriminationTree.removeRedundantDiscriminators();
    }


    /***
     * Get a node and discriminate it from its longest prefix by finding a new discriminator
     *  and place it in the discrimination Tree
     * @param uaNode is a tttNode which we want to discriminate it form its longest prefix
     * @return the tttNode belong to the longest prefix of ua
     */
    private TTTNode<I> discriminateLongestPrefix(TTTNode<I> uaNode) throws Exception {
        Word<I> ua = uaNode.sequenceAccess;
        Word<I> u = ua.prefix(ua.size() - 1);
        I a = ua.lastSymbol();
        TTTNode<I> vNode = equivalenceStateMap.get(u);
        Word<I> v = vNode.sequenceAccess;

        // find discriminator between u and v
        Word<I> discriminator = discriminationTree.findDiscriminator(v, ua);
        Word<I> newDiscriminator = new WordBuilder<I>().append(a).append(discriminator).toWord();

        // add new node to hypothesis
        TTTNode<I> uNode = createState(u, false);
        addToEquivalenceStateMap(u, uNode);
        hypothesis.addTransition(uNode.id, a, uaNode.id);

        // new node to discrimination tree
        discriminationTree.discriminate(newDiscriminator, uNode);

        return uNode;
    }

    /***
     * Check if a dfa has a specific transition from a origin
     * @param dfa the DFA
     * @param originId the id of origin state
     * @param transition the symbol of transition
     * @return true if origin state has transition otherwise false
     */
    private boolean hasTransition(CompactDFA<I> dfa, int originId, I transition) {
        return dfa.getTransition(originId, transition) == null;
    }


    /***
     * define a new state in our hypothesis and create a TTTNode from it
     * @param sequenceAccess the sequence access of a state
     * @return a TTTNode of the given state
     */
    private TTTNode<I> createState(Word<I> sequenceAccess, Boolean isTemp) {
        boolean accepting = this.teacher.membershipQuery(sequenceAccess);
        int state_id;
        if (sequenceAccess.size() > 0) { //if not initial state (check transition of prefix)
            state_id = hypothesis.addState(accepting);
            int origin_id = hypothesis.getState(sequenceAccess.prefix(sequenceAccess.size() - 1));
            I transition = sequenceAccess.lastSymbol();
            if (isTemp) {
                if (hasTransition(hypothesis, origin_id, transition))
                    hypothesis.addTransition(origin_id, transition, state_id);
            } else {
                hypothesis.removeAllTransitions(origin_id, transition);
                hypothesis.addTransition(origin_id, transition, state_id);
            }
        } else { //if initial state
            state_id = hypothesis.addInitialState(accepting);
        }
        return new TTTNode<>(state_id, sequenceAccess, accepting);
    }


    /***
     * for a given state update all transitions
     * @param node the TTTNode of a state
     */
    private void expandStateTransitions(TTTNode<I> node) throws Exception {
        for (I symbol : alphabet) {

            Word<I> newSequence = node.sequenceAccess.append(symbol);
            DTLeaf<I> newPose = discriminationTree.sift(newSequence);

            int destStateId;
            if (newPose instanceof EmptyDTLeaf) {
                throw new Exception("");
            } else {
                destStateId = newPose.state.id;
                hypothesis.removeAllTransitions(node.id, symbol);
                hypothesis.addTransition(node.id, symbol, destStateId);
                addToEquivalenceStateMap(newSequence, newPose.state);
            }
        }
    }


    private void addToEquivalenceStateMap(Word<I> sequence, TTTNode<I> node) {
        equivalenceStateMap.put(sequence, node);
    }


    public boolean tempSpanningTreeContain(Word<I> prefix) {
        for (TTTNode<I> node : tempSpanningTree) {
            if (node.sequenceAccess.equals(prefix))
                return true;
        }
        return false;
    }

    @Override
    public boolean membershipQuery(Word<I> inputString) {
        return hypothesis.computeOutput(inputString);
    }


}