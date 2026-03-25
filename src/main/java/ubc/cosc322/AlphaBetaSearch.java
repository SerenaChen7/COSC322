package ubc.cosc322;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class AlphaBetaSearch {
    private static final int WIN_SCORE = 1_000_000;
    private static final int NEG_INF = -WIN_SCORE * 2;
    private static final int POS_INF = WIN_SCORE * 2;
    private static final int DEFAULT_MAX_DEPTH = 5;
    private static final long DEFAULT_SOFT_LIMIT_MILLIS = 27_000L;
    private static final int DEPTH_ONE_ROOT_MOVE_LIMIT = 192;
    private static final int ROOT_MOVE_LIMIT = 96;
    private static final int CHILD_MOVE_LIMIT = 24;
    private static final int MAX_PLY = 20;
    private static final int ASPIRATION_DELTA = 80;

    private final long softLimitMillis;
    private final int maxDepth;

    public AlphaBetaSearch() {
        this(DEFAULT_SOFT_LIMIT_MILLIS, DEFAULT_MAX_DEPTH);
    }

    public AlphaBetaSearch(long softLimitMillis, int maxDepth) {
        this.softLimitMillis = softLimitMillis;
        this.maxDepth = maxDepth;
    }

    public static class SearchResult {
        private final AmazonsMove move;
        private final int score;
        private final int depth;
        private final long nodes;
        private final long elapsedMillis;

        SearchResult(AmazonsMove move, int score, int depth, long nodes, long elapsedMillis) {
            this.move = move;
            this.score = score;
            this.depth = depth;
            this.nodes = nodes;
            this.elapsedMillis = elapsedMillis;
        }

        public AmazonsMove getMove() { return move; }
        public int getScore() { return score; }
        public int getDepth() { return depth; }
        public long getNodes() { return nodes; }
        public long getElapsedMillis() { return elapsedMillis; }
    }

    public SearchResult chooseMove(AmazonsBoardState board, int sideToMove) {
        long startMillis = System.currentTimeMillis();
        long deadlineMillis = startMillis + softLimitMillis;
        List<AmazonsMove> legalMoves = board.generateMoves(sideToMove);
        if (legalMoves.isEmpty()) {
            return new SearchResult(null, -WIN_SCORE, 0, 0L, System.currentTimeMillis() - startMillis);
        }

        AmazonsMove bestMove = legalMoves.get(0);
        int bestScore = NEG_INF;
        int bestDepth = 0;

        SearchWorker worker = new SearchWorker(board, deadlineMillis);
        AmazonsMove preferredMove = null;

        for (int depth = 1; depth <= maxDepth; depth++) {
            if (System.currentTimeMillis() >= deadlineMillis) {
                break;
            }

            List<AmazonsMove> orderedMoves = orderRootMoves(board, sideToMove, preferredMove, depth);

    
            int alpha, beta;
            if (depth > 2 && bestScore != NEG_INF) {
                alpha = bestScore - ASPIRATION_DELTA;
                beta  = bestScore + ASPIRATION_DELTA;
            } else {
                alpha = NEG_INF;
                beta  = POS_INF;
            }

            AmazonsMove depthBestMove = null;
            int depthBestScore = NEG_INF;
            boolean timedOut = false;

            aspirationLoop:
            while (true) {
                int searchAlpha = alpha;
                depthBestMove = null;
                depthBestScore = NEG_INF;
                boolean firstMove = true;

                for (AmazonsMove move : orderedMoves) {
                    board.applyMove(move, sideToMove);
                    int score;
                    if (firstMove) {
                        score = -worker.negamax(
                            AmazonsBoardState.opponent(sideToMove), depth - 1,
                            -beta, -searchAlpha, 1
                        );
                        firstMove = false;
                    } else {
                        score = -worker.negamax(
                            AmazonsBoardState.opponent(sideToMove), depth - 1,
                            -searchAlpha - 1, -searchAlpha, 1
                        );
                        if (!worker.timedOut && score > searchAlpha && score < beta) {
                            score = -worker.negamax(
                                AmazonsBoardState.opponent(sideToMove), depth - 1,
                                -beta, -searchAlpha, 1
                            );
                        }
                    }
                    board.undoMove(move, sideToMove);

                    if (worker.timedOut) {
                        timedOut = true;
                        break aspirationLoop;
                    }

                    if (score > depthBestScore) {
                        depthBestScore = score;
                        depthBestMove = move;
                    }
                    if (score > searchAlpha) {
                        searchAlpha = score;
                    }
                }

                if (depthBestScore <= alpha && alpha != NEG_INF) {
                    alpha = NEG_INF; 
                } else if (depthBestScore >= beta && beta != POS_INF) {
                    beta = POS_INF; 
                } else {
                    break;
                }
            }

            if (!timedOut && depthBestMove != null) {
                bestMove = depthBestMove;
                bestScore = depthBestScore;
                bestDepth = depth;
                preferredMove = bestMove;
            }
        }

        return new SearchResult(bestMove, bestScore, bestDepth, worker.nodes, System.currentTimeMillis() - startMillis);
    }

    private List<AmazonsMove> orderRootMoves(AmazonsBoardState board, int player,
                                              AmazonsMove prioritizedMove, int depthRemaining) {
        List<AmazonsMove> moves = board.generateMoves(player);
        if (moves.size() <= 1) {
            return moves;
        }

        List<ScoredMove> scoredMoves = new ArrayList<ScoredMove>(moves.size());
        for (AmazonsMove move : moves) {
            board.applyMove(move, player);
            int score = quickMoveScore(board, player, move, prioritizedMove);
            board.undoMove(move, player);
            scoredMoves.add(new ScoredMove(move, score));
        }

        Collections.sort(scoredMoves, Comparator.comparingInt(ScoredMove::getScore).reversed());

        int limit = depthRemaining <= 1
            ? Math.min(scoredMoves.size(), DEPTH_ONE_ROOT_MOVE_LIMIT)
            : Math.min(scoredMoves.size(), ROOT_MOVE_LIMIT);

        List<AmazonsMove> ordered = new ArrayList<AmazonsMove>(limit);
        for (int i = 0; i < limit; i++) {
            ordered.add(scoredMoves.get(i).move);
        }
        return ordered;
    }

    private static int quickMoveScore(AmazonsBoardState board, int player,
                                       AmazonsMove move, AmazonsMove prioritizedMove) {
        int opponent = AmazonsBoardState.opponent(player);
        int score = 6 * centerBias(move) + 2 * centerBias(move.getArrowRow(), move.getArrowCol());
        if (move.equals(prioritizedMove)) {
            score += 2_000_000;
        }
        if (!board.hasAnyMoves(opponent)) {
            score += 1_000_000;
        }
        score += 6 * board.countDestinationsFrom(move.getToRow(), move.getToCol());
        score += 20 * (board.countActiveQueens(player) - board.countActiveQueens(opponent));
        return score;
    }

    private static int centerBias(AmazonsMove move) {
        return centerBias(move.getToRow(), move.getToCol());
    }

    private static int centerBias(int row, int col) {
        int rowDistance = Math.abs(5 - row);
        int colDistance = Math.abs(5 - col);
        return 20 - (rowDistance + colDistance);
    }

    private static class SearchWorker {
        private final AmazonsBoardState board;
        private final long deadlineMillis;
        boolean timedOut;
        long nodes;

        private final AmazonsMove[] killer0 = new AmazonsMove[MAX_PLY];
        private final AmazonsMove[] killer1 = new AmazonsMove[MAX_PLY];

        SearchWorker(AmazonsBoardState board, long deadlineMillis) {
            this.board = board;
            this.deadlineMillis = deadlineMillis;
        }

        int negamax(int player, int depth, int alpha, int beta, int ply) {
            nodes++;
            if (isExpired()) {
                return 0;
            }

            if (!board.hasAnyMoves(player)) {
                return -WIN_SCORE + ply;
            }
            if (depth == 0) {
                return board.evaluate(player);
            }

            List<AmazonsMove> orderedMoves = orderMoves(player, depth, ply);
            int bestScore = NEG_INF;
            boolean firstMove = true;
            int opponent = AmazonsBoardState.opponent(player);

            for (AmazonsMove move : orderedMoves) {
                board.applyMove(move, player);
                int score;

                if (firstMove) {
                    score = -negamax(opponent, depth - 1, -beta, -alpha, ply + 1);
                    firstMove = false;
                } else {
                    score = -negamax(opponent, depth - 1, -alpha - 1, -alpha, ply + 1);
                    // Re-search with full window if score is inside the window.
                    if (!timedOut && score > alpha && score < beta) {
                        score = -negamax(opponent, depth - 1, -beta, -alpha, ply + 1);
                    }
                }
                board.undoMove(move, player);

                if (score > bestScore) {
                    bestScore = score;
                }
                if (score > alpha) {
                    alpha = score;
                }
                if (alpha >= beta) {
                    // Beta cutoff: store killer move.
                    storeKiller(move, ply);
                    break;
                }
            }

            return bestScore;
        }

        private void storeKiller(AmazonsMove move, int ply) {
            if (ply >= MAX_PLY) return;
            if (!move.equals(killer0[ply])) {
                killer1[ply] = killer0[ply];
                killer0[ply] = move;
            }
        }

        private List<AmazonsMove> orderMoves(int player, int depthRemaining, int ply) {
            List<AmazonsMove> moves = board.generateMoves(player);
            if (moves.size() <= 1) {
                return moves;
            }

            AmazonsMove k0 = ply < MAX_PLY ? killer0[ply] : null;
            AmazonsMove k1 = ply < MAX_PLY ? killer1[ply] : null;

            List<ScoredMove> scoredMoves = new ArrayList<ScoredMove>(moves.size());
            int evaluated = 0;
            for (AmazonsMove move : moves) {
                if (evaluated++ % 50 == 0 && isExpired()) {
                    break;
                }
                board.applyMove(move, player);
                int opponent = AmazonsBoardState.opponent(player);
                int score = 6 * centerBias(move) + 2 * centerBias(move.getArrowRow(), move.getArrowCol());
                if (!board.hasAnyMoves(opponent)) {
                    score += 1_000_000;
                }
                score += 6 * board.countDestinationsFrom(move.getToRow(), move.getToCol());
                score += 20 * (board.countActiveQueens(player) - board.countActiveQueens(opponent));
                board.undoMove(move, player);

                // Killer move bonuses for better ordering.
                if (move.equals(k0)) {
                    score += 500_000;
                } else if (move.equals(k1)) {
                    score += 400_000;
                }

                scoredMoves.add(new ScoredMove(move, score));
            }

            Collections.sort(scoredMoves, Comparator.comparingInt(ScoredMove::getScore).reversed());

            int moveLimit = Math.min(scoredMoves.size(), CHILD_MOVE_LIMIT);
            List<AmazonsMove> ordered = new ArrayList<AmazonsMove>(moveLimit);
            for (int i = 0; i < moveLimit; i++) {
                ordered.add(scoredMoves.get(i).move);
            }
            return ordered;
        }

        private boolean isExpired() {
            if (timedOut) {
                return true;
            }
            if (nodes % 100 == 0 && System.currentTimeMillis() >= deadlineMillis) {
                timedOut = true;
                return true;
            }
            return false;
        }
    }

    private static class ScoredMove {
        final AmazonsMove move;
        final int score;

        ScoredMove(AmazonsMove move, int score) {
            this.move = move;
            this.score = score;
        }

        int getScore() {
            return score;
        }
    }
}
