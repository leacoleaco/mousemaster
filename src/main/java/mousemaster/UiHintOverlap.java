package mousemaster;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A UI hint sits on its element, so the boxes of two hints can overlap. The labels of such
 * hints are made to begin with different keys: the first key pressed then hides one of them.
 */
final class UiHintOverlap {

    private UiHintOverlap() {
    }

    /**
     * For each box, the indexes of the boxes it overlaps. Boxes that only touch do not
     * overlap, and a hint without a box (null, or empty) overlaps none.
     */
    static List<List<Integer>> overlaps(List<Rectangle> boxes) {
        List<List<Integer>> overlaps = new ArrayList<>(boxes.size());
        List<Integer> fromTheLeft = new ArrayList<>(boxes.size());
        for (int index = 0; index < boxes.size(); index++) {
            overlaps.add(new ArrayList<>(0));
            Rectangle box = boxes.get(index);
            if (box != null && !box.isEmpty())
                fromTheLeft.add(index);
        }
        fromTheLeft.sort(Comparator.comparingInt(index -> boxes.get(index).x()));
        for (int position = 0; position < fromTheLeft.size(); position++) {
            int index = fromTheLeft.get(position);
            Rectangle box = boxes.get(index);
            for (int otherPosition = position + 1; otherPosition < fromTheLeft.size();
                 otherPosition++) {
                int otherIndex = fromTheLeft.get(otherPosition);
                Rectangle otherBox = boxes.get(otherIndex);
                // Neither does any box further right.
                if (otherBox.x() >= box.x() + box.width())
                    break;
                if (otherBox.y() < box.y() + box.height() &&
                    box.y() < otherBox.y() + otherBox.height()) {
                    overlaps.get(index).add(otherIndex);
                    overlaps.get(otherIndex).add(index);
                }
            }
        }
        return overlaps;
    }

    /**
     * The labels, swapped between hints so that two hints whose boxes overlap begin with
     * different keys wherever the labels allow it. The first {@code fixedCount} hints keep
     * theirs.
     * <p>
     * A swap is only made when it leaves fewer overlapping hints that begin alike. A label
     * is swapped with one of as many keys when one helps, so that the hints that were given
     * the shortest labels keep them: the longest labels often all begin with the same key,
     * and only then is a label swapped with one of another length. Of the swaps that leave
     * the fewest hints that begin alike, the one with the nearest hint is made. Hints that
     * are too many on each other for the keys that begin a label keep some that begin alike.
     */
    static List<List<Key>> labelsBeginningApart(List<List<Key>> labels,
                                                List<List<Integer>> overlaps,
                                                int fixedCount) {
        int count = labels.size();
        Map<Key, Integer> beginningByKey = new HashMap<>();
        int[] beginnings = new int[count];
        for (int index = 0; index < count; index++) {
            Key firstKey = labels.get(index).getFirst();
            Integer beginning = beginningByKey.get(firstKey);
            if (beginning == null) {
                beginning = beginningByKey.size();
                beginningByKey.put(firstKey, beginning);
            }
            beginnings[index] = beginning;
        }
        // For each hint and each beginning, how many of the hints it overlaps have it.
        int[][] overlapCounts = new int[count][beginningByKey.size()];
        for (int index = 0; index < count; index++)
            for (int otherIndex : overlaps.get(index))
                overlapCounts[index][beginnings[otherIndex]]++;
        // The index of the label each hint has.
        int[] labelIndexes = new int[count];
        for (int index = 0; index < count; index++)
            labelIndexes[index] = index;
        boolean[] overlapsHint = new boolean[count];
        boolean swapped = true;
        // Every swap leaves fewer hints that begin alike, so this ends.
        while (swapped) {
            swapped = false;
            for (int index = fixedCount; index < count; index++) {
                int beginning = beginnings[index];
                int alikeCount = overlapCounts[index][beginning];
                if (alikeCount == 0)
                    continue;
                for (int otherIndex : overlaps.get(index))
                    overlapsHint[otherIndex] = true;
                int keyCount = labels.get(labelIndexes[index]).size();
                int bestIndex = -1;
                int bestGain = 0;
                boolean bestHasAsManyKeys = false;
                for (int otherIndex = fixedCount; otherIndex < count; otherIndex++) {
                    int otherBeginning = beginnings[otherIndex];
                    if (otherBeginning == beginning)
                        continue;
                    // The two hints do not begin alike, before or after: when they overlap,
                    // each is counted in what the other would begin like.
                    int gain = alikeCount + overlapCounts[otherIndex][otherBeginning]
                               - overlapCounts[index][otherBeginning]
                               - overlapCounts[otherIndex][beginning]
                               + (overlapsHint[otherIndex] ? 2 : 0);
                    if (gain <= 0)
                        continue;
                    boolean hasAsManyKeys =
                            labels.get(labelIndexes[otherIndex]).size() == keyCount;
                    boolean better;
                    if (bestIndex == -1 || hasAsManyKeys != bestHasAsManyKeys)
                        better = bestIndex == -1 || hasAsManyKeys;
                    else if (gain != bestGain)
                        better = gain > bestGain;
                    else
                        better = Math.abs(otherIndex - index) < Math.abs(bestIndex - index);
                    if (better) {
                        bestIndex = otherIndex;
                        bestGain = gain;
                        bestHasAsManyKeys = hasAsManyKeys;
                    }
                }
                for (int otherIndex : overlaps.get(index))
                    overlapsHint[otherIndex] = false;
                if (bestIndex == -1)
                    continue;
                int otherBeginning = beginnings[bestIndex];
                for (int otherIndex : overlaps.get(index)) {
                    overlapCounts[otherIndex][beginning]--;
                    overlapCounts[otherIndex][otherBeginning]++;
                }
                for (int otherIndex : overlaps.get(bestIndex)) {
                    overlapCounts[otherIndex][otherBeginning]--;
                    overlapCounts[otherIndex][beginning]++;
                }
                beginnings[index] = otherBeginning;
                beginnings[bestIndex] = beginning;
                int labelIndex = labelIndexes[index];
                labelIndexes[index] = labelIndexes[bestIndex];
                labelIndexes[bestIndex] = labelIndex;
                swapped = true;
            }
        }
        List<List<Key>> apart = new ArrayList<>(count);
        for (int index = 0; index < count; index++)
            apart.add(labels.get(labelIndexes[index]));
        return apart;
    }

}
