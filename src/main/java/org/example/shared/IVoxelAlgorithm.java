package org.example.shared;

import java.util.HashMap;
import java.util.List;
import org.example.model.Pattern3D;

public interface IVoxelAlgorithm {

  int[][][] solve();

  Vector3<Integer> getInputSize();

  List<Pattern3D> getPatterns();

  List<HashMap<Vector3<Integer>, Integer>> getPatternsByPosition();
}
