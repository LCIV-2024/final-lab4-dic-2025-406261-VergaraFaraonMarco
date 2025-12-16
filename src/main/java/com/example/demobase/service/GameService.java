package com.example.demobase.service;

import com.example.demobase.dto.GameDTO;
import com.example.demobase.dto.GameResponseDTO;
import com.example.demobase.model.Game;
import com.example.demobase.model.GameInProgress;
import com.example.demobase.model.Player;
import com.example.demobase.model.Word;
import com.example.demobase.repository.GameInProgressRepository;
import com.example.demobase.repository.GameRepository;
import com.example.demobase.repository.PlayerRepository;
import com.example.demobase.repository.WordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GameService {
    
    private final GameRepository gameRepository;
    private final GameInProgressRepository gameInProgressRepository;
    private final PlayerRepository playerRepository;
    private final WordRepository wordRepository;
    
    private static final int MAX_INTENTOS = 7;
    private static final int PUNTOS_PALABRA_COMPLETA = 20;
    private static final int PUNTOS_POR_LETRA = 1;

    @Transactional
    public GameResponseDTO startGame(Long playerId) {
        // Validar que el jugador existe
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new RuntimeException("No se encontró el jugador."));

        Word word = wordRepository.findRandomWord()
                .orElseThrow(() -> new RuntimeException("No hay palabras disponibles."));

        // Verificar si ya existe una partida en curso para este jugador y esta palabra
        Optional<GameInProgress> existing = gameInProgressRepository.findByJugadorAndPalabra(playerId, word.getId());

        GameInProgress game;
        if (existing.isPresent()) {
            game = existing.get();
        } else {
            // Marcar la palabra como utilizada
            word.setUtilizada(true);
            wordRepository.save(word);

            // Crear nueva partida en curso
            game = new GameInProgress();
            game.setJugador(player);
            game.setPalabra(word);
            game.setLetrasIntentadas("");
            game.setIntentosRestantes(MAX_INTENTOS);
            game.setFechaInicio(LocalDateTime.now());
            game = gameInProgressRepository.save(game);
        }
        return buildResponseFromGameInProgress(game);
    }

    @Transactional
    public GameResponseDTO makeGuess(Long playerId, Character letra) {
        // Validar que el jugador existe
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new RuntimeException("No se encontró el jugador."));

        // Buscar la partida en curso más reciente del jugador
        List<GameInProgress> games = gameInProgressRepository.findByJugadorIdOrderByFechaInicioDesc(playerId);
        if (games.isEmpty()) {
            throw new RuntimeException("No hay partida en curso para este jugador.");
        }
        GameInProgress game = games.get(0);

        // Letra a mayúsculas
        letra = Character.toUpperCase(letra);

        String palabraStr = game.getPalabra().getPalabra().toUpperCase();
        Set<Character> intentadas = stringToCharSet(game.getLetrasIntentadas());

        // Verificar si la letra ya fue intentada para evitar guardados innecesarios
        if (!intentadas.contains(letra)) {
            intentadas.add(letra);
            game.setLetrasIntentadas(charSetToString(intentadas));

            // Decrementar intentos si la letra es incorrecta
            if (palabraStr.indexOf(letra) == -1) {
                game.setIntentosRestantes(game.getIntentosRestantes() - 1);
            }

            GameResponseDTO response = buildResponseFromGameInProgress(game);

            // Si el juego terminó guardar y limpiar
            if (response.getPalabraCompleta() || game.getIntentosRestantes() <= 0) {
                saveGame(player, game.getPalabra(), response.getPalabraCompleta(), response.getPuntajeAcumulado());
                gameInProgressRepository.delete(game);
            } else {
                gameInProgressRepository.save(game);
            }
            return response;
        }

        return buildResponseFromGameInProgress(game);
    }
    
    private GameResponseDTO buildResponseFromGameInProgress(GameInProgress gameInProgress) {
        String palabra = gameInProgress.getPalabra().getPalabra().toUpperCase();
        Set<Character> letrasIntentadas = stringToCharSet(gameInProgress.getLetrasIntentadas());
        String palabraOculta = generateHiddenWord(palabra, letrasIntentadas);
        boolean palabraCompleta = palabraOculta.equals(palabra);
        
        GameResponseDTO response = new GameResponseDTO();
        response.setPalabraOculta(palabraOculta);
        response.setLetrasIntentadas(new ArrayList<>(letrasIntentadas));
        response.setIntentosRestantes(gameInProgress.getIntentosRestantes());
        response.setPalabraCompleta(palabraCompleta);
        
        int puntaje = calculateScore(palabra, letrasIntentadas, palabraCompleta, gameInProgress.getIntentosRestantes());
        response.setPuntajeAcumulado(puntaje);
        
        return response;
    }
    
    private Set<Character> stringToCharSet(String str) {
        Set<Character> set = new HashSet<>();
        if (str != null && !str.isEmpty()) {
            String[] chars = str.split(",");
            for (String c : chars) {
                if (!c.trim().isEmpty()) {
                    set.add(c.trim().charAt(0));
                }
            }
        }
        return set;
    }
    
    private String charSetToString(Set<Character> set) {
        if (set == null || set.isEmpty()) {
            return "";
        }
        return set.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }
    
    private int calculateScore(String palabra, Set<Character> letrasIntentadas, boolean palabraCompleta, int intentosRestantes) {
        if (palabraCompleta) {
            return PUNTOS_PALABRA_COMPLETA;
        } else if (intentosRestantes == 0) {
            // Contar letras correctas encontradas
            long letrasCorrectas = letrasIntentadas.stream()
                    .filter(letra -> palabra.indexOf(letra) >= 0)
                    .count();
            return (int) (letrasCorrectas * PUNTOS_POR_LETRA);
        }
        return 0;
    }
    
    private String generateHiddenWord(String palabra, Set<Character> letrasIntentadas) {
        StringBuilder hidden = new StringBuilder();
        for (char c : palabra.toCharArray()) {
            if (letrasIntentadas.contains(c) || c == ' ') {
                hidden.append(c);
            } else {
                hidden.append('_');
            }
        }
        return hidden.toString();
    }
    
    @Transactional
    private void saveGame(Player player, Word word, boolean ganado, int puntaje) {
        // Asegurar que la palabra esté marcada como utilizada
        if (!word.getUtilizada()) {
            word.setUtilizada(true);
            wordRepository.save(word);
        }
        
        Game game = new Game();
        game.setJugador(player);
        game.setPalabra(word);
        game.setResultado(ganado ? "GANADO" : "PERDIDO");
        game.setPuntaje(puntaje);
        game.setFechaPartida(LocalDateTime.now());
        gameRepository.save(game);
    }
    
    public List<GameDTO> getGamesByPlayer(Long playerId) {
        return gameRepository.findByJugadorId(playerId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }
    
    public List<GameDTO> getAllGames() {
        return gameRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }
    
    private GameDTO toDTO(Game game) {
        GameDTO dto = new GameDTO();
        dto.setId(game.getId());
        dto.setIdJugador(game.getJugador().getId());
        dto.setNombreJugador(game.getJugador().getNombre());
        dto.setResultado(game.getResultado());
        dto.setPuntaje(game.getPuntaje());
        dto.setFechaPartida(game.getFechaPartida());
        dto.setPalabra(game.getPalabra() != null ? game.getPalabra().getPalabra() : null);
        return dto;
    }
}

