package org.example.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.stream.Collectors;
import org.example.shared.IVoxelAlgorithm;
import org.example.shared.Vector3;

/**
 * This class implements a modified version of the WFC algorithm in 3D specialized for Voxel Models.
 */
public class VoxelWfcModel implements IVoxelAlgorithm {


  //Parameters
  private Grid3D input;
  private int patternSize;
  private boolean rotation;
  private double avoidEmptyPattern;
  private Vector3<Integer> outputSize;

  //Internal Parameters
  private int maximumTries = 500;
  private int maxPropagationTries = 10;

  //Internal Datastructures
  private Random rng;

  private List<Pattern3D> patterns;
  private List<Double> patternFrequency;
  private HashMap<Integer, Neighbours> patternNeighbours;
  private List<HashMap<Vector3<Integer>, Integer>> patternsByPosition;
  private List<List<Integer>> wave;
  private List<Double> entropy;
  private Double baseEntropy;

  /**
   * Initialization of the algorithm
   *
   * @param input 3D-Array of input model
   * @param patternSize size of NxNxN patterns that should be extracted
   * @param outputSize size of the output
   * @param rotation should rotation be used
   * @param avoidEmptyPattern how much should empty space be avoided
   * @param rngSeed seed for reproduceability
   */
  public VoxelWfcModel(
      int[][][] input,
      int patternSize,
      Vector3<Integer> outputSize,
      boolean rotation,
      double avoidEmptyPattern,
      long rngSeed
  ) {
    this.input = new Grid3D(input, patternSize);
    this.patternSize = patternSize;
    this.rotation = rotation;
    this.avoidEmptyPattern = avoidEmptyPattern;
    this.outputSize = new Vector3<>(
        outputSize.getX() + 2,
        outputSize.getY() + 2,
        outputSize.getZ() + 2
    ); // + 2 beacuse of padding
    this.rng = new Random(rngSeed);

    findPatterns();
    findNeighbours();
  }

  /**
   * Generate a new model with the given parameters.
   *
   * @return 3D-Array of the output model.
   */
  public int[][][] solve() {
    //Initialization
    ArrayList<Integer> borderCells = initializeWave();
    baseEntropy = getEntropy(0);
    int collapsedCells = (int) wave.stream().filter(l -> l.size() == 1).count();
    int tries = 0;
    int propagationTries = 0;
    this.entropy = wave.stream().map(this::getEntropy).collect(Collectors.toList());

    System.out.print("Attempt number: ");
    //main loop
    while (collapsedCells < outputSize.getX() * outputSize.getY() * outputSize.getZ() && tries < maximumTries) {

      //create backup if propagation fails
      List<List<Integer>> snapshot = new ArrayList<>();
      wave.forEach(cell -> snapshot.add(new ArrayList<>(cell)));

      //solve
      boolean success = false;
      if (!borderCells.isEmpty()) {
        //Border cells need to be propagated first
        success = propagate(borderCells);
        borderCells.clear();
      } else {
        //collapse min entropy cell
        int minEntropyIndex = getLowestEntropyCell();
        wave.set(minEntropyIndex, Collections.singletonList(selectRandomPattern(minEntropyIndex)));
        this.entropy.set(minEntropyIndex, getEntropy(minEntropyIndex));
        success = propagate(Collections.singletonList(minEntropyIndex));
      }

      if (!success) {
        if (propagationTries >= maxPropagationTries) {
          tries++;
          System.out.print(tries + ",");
          borderCells = initializeWave();
          this.entropy = wave.stream().map(this::getEntropy).collect(Collectors.toList());
        } else {
          wave = snapshot;
          this.entropy = wave.stream().map(this::getEntropy).collect(Collectors.toList());
          propagationTries++;
        }
      } else {
        propagationTries = 0;
      }
      collapsedCells = (int) wave.stream().filter(l -> l.size() == 1).count();
    }
    System.out.println();
    if (tries >= maximumTries) {
      System.out.println("No solution after " + tries + " tries.");
      return null;
    } else {
      System.out.println("Success after " + tries + " tries.");
    }

    int[][][] array = generateOutput();
    return array;
  }

  public Vector3<Integer> getInputSize() {
    return input.size();
  }

  public List<Pattern3D> getPatterns() {
    return patterns;
  }

  public List<HashMap<Vector3<Integer>, Integer>> getPatternsByPosition() {
    return patternsByPosition;
  }

  /**
   * Extract and process patterns from input model
   */
  private void findPatterns() {

    ArrayList<Grid3D> inputs = new ArrayList<>();
    inputs.add(input);
    if (rotation) {
      for (int i = 0; i < 3; i++) {
        inputs.add(inputs.get(inputs.size() - 1).getYRotated());
      }
    }

    Pattern3D emptyPattern = new Pattern3D(patternSize);
    this.patterns = new ArrayList<>();
    //Add empty pattern at pos 0
    this.patterns.add(emptyPattern);
    this.patternsByPosition = new ArrayList<>();
    this.patternFrequency = new ArrayList<>();
    this.patternFrequency.add(0.000001);

    for (int i = 0; i < inputs.size(); i++) {
      Grid3D currentInput = inputs.get(i);
      int boundX = currentInput.size().getX();
      int boundY = currentInput.size().getY();
      int boundZ = currentInput.size().getZ();
      patternsByPosition.add(new HashMap<>());
      for (int x = 0; x <= boundX; x += patternSize) {
        for (int y = 0; y <= boundY; y += patternSize) {
          for (int z = 0; z <= boundZ; z += patternSize) {
            ArrayList<Pattern3D> tempPatterns = new ArrayList<>();
            Pattern3D currentPattern = currentInput.getPatternAtPosition(new Vector3(x, y, z), patternSize);
            if (currentPattern != null) {
              tempPatterns.add(currentPattern);
              Vector3<Integer> patternPosition = new Vector3<>(x / patternSize, y / patternSize, z / patternSize);
              for (Pattern3D pattern : tempPatterns) {
                if (!this.patterns.contains(pattern)) {
                  this.patterns.add(pattern);
                  this.patternsByPosition.get(i).put(patternPosition, patterns.size() - 1);
                  this.patternFrequency.add(1d);
                } else {
                  int patternIndex = this.patterns.indexOf(pattern);
                  this.patternsByPosition.get(i).put(patternPosition, patternIndex);
                  Double patternFrequency = this.patternFrequency.get(patternIndex);
                  this.patternFrequency.set(patternIndex, patternFrequency + 1);
                }
              }
            }
          }
        }
      }
    }

    //remove all the emplty patterns from padding
    var x = input.size().getX() / patternSize;
    var y = input.size().getY() / patternSize;
    var z = input.size().getZ() / patternSize;
    int padding = (x * y * z) - ((x - 2) * (y - 1) * (z - 2));
    if (rotation) {
      padding *= 4;
    }
    double freqWithoutPadding = this.patternFrequency.get(0) - padding;
    this.patternFrequency.set(
        0,
        freqWithoutPadding > 0 ? freqWithoutPadding * (1 - avoidEmptyPattern) /* + 3 TODO magic number remove */ : 0.01
    );

    int totalPatternCount =
        (input.size().getX() / patternSize) * (input.size().getY() / patternSize) * (input.size().getZ() / patternSize);
    if (rotation) {
      totalPatternCount *= 4;
    }
    normalizePatternFrequency(totalPatternCount);
  }

  /**
   * Find adjacency constraints
   */
  private void findNeighbours() {

    this.patternNeighbours = new HashMap<>();
    for (int i = 0; i < patterns.size(); i++) {
      this.patternNeighbours.put(i, new Neighbours());
    }
    // Border Pattern
    this.patternNeighbours.put(-1, new Neighbours());

    patternsByPosition.forEach(currentRotation -> {
      currentRotation.forEach((position, patternIndex) -> {
        for (int i = 0; i < 6; i++) {
          Direction3D dir = Direction3D.values()[i];
          Vector3<Integer> dirV = Utils.DIRECTIONS.get(i);

          Vector3<Integer> newPos =
              new Vector3<>(position.getX() + dirV.getX(), position.getY() + dirV.getY(), position.getZ() + dirV.getZ());

          if (currentRotation.containsKey(newPos)) {
            this.patternNeighbours.get(patternIndex).addNeighbour(dir, currentRotation.get(newPos));
          } else if (dir == Direction3D.DOWN) {
            this.patternNeighbours.get(patternIndex).addNeighbour(dir, -1);
            this.patternNeighbours.get(-1).addNeighbour(Utils.opposite(dir), patternIndex);
          }
        }
      });
    });
  }

  /**
   * Initialize the wave by adding possible patterns to each cell and adding a padding of empty (index: 0) and floor(index: -1)
   * cells around the edges.
   *
   * @return List of edge and floor cell indices
   */
  private ArrayList<Integer> initializeWave() {
    ArrayList<Integer> floorCells = new ArrayList<>();
    ArrayList<Integer> paddingCells = new ArrayList<>();
    wave = new ArrayList<>();
    for (int x = 0; x < outputSize.getX(); x++) {
      for (int y = 0; y < outputSize.getY(); y++) {
        for (int z = 0; z < outputSize.getZ(); z++) {
          wave.add(new ArrayList<>());
          List<Integer> waveCell = wave.get(wave.size() - 1);
          if (y == outputSize.getY() - 1) {
            waveCell.add(-1);
            floorCells.add(wave.size() - 1);
          } else if (x == 0 || x == outputSize.getX() - 1 || y == 0 || z == 0 || z == outputSize.getZ() - 1) {
            waveCell.add(0);
            paddingCells.add(wave.size() - 1);
          } else {
            for (int i = 0; i < patterns.size(); i++) {
              waveCell.add(i);
            }
          }
        }
      }
    }
    floorCells.addAll(paddingCells);
    return floorCells;
  }

  /**
   * Propagate changes through the model until state is consistent with adjacency constraints or a contradiction happens.
   *
   * @param cellIndices Cells to propagate
   * @return true -> success, false -> contradiction
   */
  private boolean propagate(List<Integer> cellIndices) {
    Queue<Integer> cellsToPropagate = new LinkedList<>();

    cellsToPropagate.addAll(cellIndices);
    while (!cellsToPropagate.isEmpty()) {
      int currentCell = cellsToPropagate.poll();

      Vector3<Integer> cellPosition = getPosFromCellIndex(currentCell);

      for (int i = 0; i < Direction3D.values().length; i++) {
        Direction3D direction = Direction3D.values()[i];
        Vector3<Integer> dir = Utils.DIRECTIONS.get(i);

        Vector3<Integer> neighbourPosition = new Vector3<Integer>(
            cellPosition.getX() + dir.getX(),
            cellPosition.getY() + dir.getY(),
            cellPosition.getZ() + dir.getZ()
        );

        if (neighbourPosition.getX() < 0
            || neighbourPosition.getX() >= outputSize.getX()
            || neighbourPosition.getY() < 0
            || neighbourPosition.getY() >= outputSize.getY()
            || neighbourPosition.getZ() < 0
            || neighbourPosition.getZ() >= outputSize.getZ()
        ) {
          continue;
        }
        List<Integer> neighbourCell = getWaveAt(neighbourPosition.getX(), neighbourPosition.getY(), neighbourPosition.getZ());

        if (neighbourCell.size() == 1 && neighbourCell.get(0) == -1) {
          continue;
        }

        HashSet<Integer> possiblePatterns = new HashSet<>();
        List<Integer> currentPatterns = wave.get(currentCell);
        for (Integer pattern : currentPatterns) {
          List<Integer> patterns = neighbourCell.stream()
              .filter(neighbourPattern -> patternNeighbours.get(pattern).neighbours.get(direction).contains(neighbourPattern))
              .collect(Collectors.toList());
          possiblePatterns.addAll(patterns);
        }

        int neighbourIndex = getCellIndexFromPos(neighbourPosition.getX(), neighbourPosition.getY(), neighbourPosition.getZ());
        List<Integer> newPatterns = new ArrayList<Integer>(possiblePatterns);
        if (newPatterns.size() < neighbourCell.size()) {
          wave.set(neighbourIndex, newPatterns);
          this.entropy.set(neighbourIndex, getEntropy(neighbourIndex));
          cellsToPropagate.add(neighbourIndex);
        }

        if (possiblePatterns.size() == 0) {/* restart algorithm */
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Generate output from collapsed wave.
   *
   * @return 3D-Array of output model
   */
  private int[][][] generateOutput() {
    int sizeX = outputSize.getX() * patternSize;
    int sizeY = (outputSize.getY() - 1) * patternSize;
    int sizeZ = outputSize.getZ() * patternSize;
    int[][][] grid = new int[sizeZ][sizeY][sizeX];
    for (int z = 0; z < outputSize.getZ(); z++) {
      for (int y = 0; y < outputSize.getY() - 1; y++) {
        for (int x = 0; x < outputSize.getX(); x++) {
          Integer patternIndex = getWaveAt(x, y, z).get(0);
          Pattern3D pattern = patternIndex >= 0 ? patterns.get(patternIndex) : new Pattern3D(this.patternSize);
          if (y >= (outputSize.getY() - 1)) {
            System.out.println(patternIndex);
          }
          for (int px = 0; px < patternSize; px++) {
            for (int py = 0; py < patternSize; py++) {
              for (int pz = 0; pz < patternSize; pz++) {
                grid[z * patternSize + pz][y * patternSize + py][x * patternSize + px] = pattern.get(px, py, pz);
              }
            }
          }
        }
      }
    }
    return grid;
  }

  /**
   * Get entropy value of given cell
   */
  private double getEntropy(int cellIndex) {
    return getEntropy(wave.get(cellIndex));
  }

  /**
   * Get entropy value of given cell
   */
  private double getEntropy(Vector3<Integer> cellPosition) {
    return getEntropy(getWaveAt(cellPosition.getX(), cellPosition.getY(), cellPosition.getZ()));
  }

  /**
   * Get entropy value of given cell
   */
  private double getEntropy(List<Integer> cell) {

    if (cell.size() == 1) {
      return 0;
    }

    double sumOfWeights = cell.stream()
        .map(index -> patternFrequency.get(index))
        .reduce(0d, Double::sum, Double::sum);

    double logSumOfWeights = cell.stream()
        .map(index -> patternFrequency.get(index))
        .reduce(0d, (sum, weight) -> sum + (weight * log2(weight)), Double::sum);

    return log2(sumOfWeights) - (logSumOfWeights / sumOfWeights) + (2e-10 * rng.nextDouble());
  }

  private int getLowestEntropyCell() {
    double min = Double.MAX_VALUE;
    int minIndex = -1;
    for (int i = 0; i < entropy.size(); i++) {
      if (entropy.get(i) > 0 && entropy.get(i) < min) {
        min = entropy.get(i);
        minIndex = i;
      }
    }
    if (minIndex == -1) {
      return 0;
    }
    return minIndex;
  }

  private int selectRandomPattern(int cellIndex) {
    List<Integer> cell = wave.get(cellIndex);

    List<Double> frequencies = cell.stream().map(pattern -> patternFrequency.get(pattern)).collect(Collectors.toList());
    double total = frequencies.stream().reduce(0d, Double::sum);

    double rand = rng.nextDouble() * total;
    double acc = 0;
    for (int i = 0; i < cell.size() - 1; i++) {
      int pattern = cell.get(i);
      acc += frequencies.get(i);
      if (acc >= rand && rand <= (acc + frequencies.get(i + 1))) {
        return pattern;
      }
    }
    return cell.get(cell.size() - 1);
  }

  //Utility methods

  private void normalizePatternFrequency(int totalPatternCount) {
    for (int i = 0; i < patternFrequency.size(); i++) {
      double repetitions = patternFrequency.get(i);
      patternFrequency.set(i, repetitions / totalPatternCount);
    }
  }

  private double log2(double value) {
    return Math.log(value) / Math.log(2);
  }

  private int getCellIndexFromPos(int x, int y, int z) {
    return x + y * outputSize.getX() + z * outputSize.getX() * outputSize.getY();
  }

  private List<Integer> getWaveAt(int x, int y, int z) {
    return wave.get(getCellIndexFromPos(x, y, z));
  }

  private Vector3<Integer> getPosFromCellIndex(int index) {
    int x = index % outputSize.getX();
    int y = (index / outputSize.getX()) % outputSize.getY();
    int z = index / (outputSize.getX() * outputSize.getY());
    return new Vector3<>(x, y, z);
  }
}
