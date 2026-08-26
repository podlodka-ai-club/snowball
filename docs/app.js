const animation = lottie.loadAnimation({
  container: document.getElementById('lottie'),
  renderer: 'svg',
  loop: true,
  autoplay: true,
  path: 'learning-loop.json'
});

const steps = [
  {
    from: 0, to: 5,
    index: '01',
    title: 'A new retail scenario arrives',
    text: 'Ice cream, a hot Saturday, and high stock. The agent has no relevant experience stored for this context yet.',
    round: 'ROUND 1 · COLD MEMORY',
    agent: 'No useful lesson yet<br><b>Chooses 10%</b>',
    memory: 'No relevant lesson',
    eval: 'Replay 0 / 10 / 20 / 30%<br>Find best action<br>Calculate regret'
  },
  {
    from: 5, to: 10,
    index: '02',
    title: 'The agent makes its first guess',
    text: 'With no useful memory to lean on, it recommends a 10% discount. This is a real action, not a hypothetical suggestion.',
    round: 'ROUND 1 · COLD MEMORY',
    agent: 'No useful lesson yet<br><b>Chooses 10%</b>',
    memory: 'No relevant lesson',
    eval: 'Waiting for outcome…'
  },
  {
    from: 10, to: 14,
    index: '03',
    title: 'The simulated market answers',
    text: 'The market simulator applies hidden demand effects and produces sales and gross profit. The agent does not know the simulator’s exact rules.',
    round: 'ROUND 1 · COLD MEMORY',
    agent: 'Chosen action<br><b>10% discount</b>',
    memory: 'No relevant lesson',
    eval: 'Outcome received'
  },
  {
    from: 14, to: 19,
    index: '04',
    title: 'The evaluator finds the mistake',
    text: 'It replays the exact same scenario at every allowed discount. 20% would have made the most profit. The 10% choice produced £43 of regret.',
    round: 'ROUND 1 · EVALUATION',
    agent: 'Chosen action<br><b>10% discount</b>',
    memory: 'No relevant lesson',
    eval: '10% → £248<br><b>20% → £291 · best</b><br>Regret: £43'
  },
  {
    from: 19, to: 22,
    index: '05',
    title: 'A reusable lesson is written',
    text: 'The result becomes durable memory: for ice cream on hot weekends with high stock, 20% outperformed 10%. Evidence count starts at one.',
    round: 'ROUND 1 · MEMORY WRITE',
    agent: 'First decision complete',
    memory: '<b>Lesson saved</b><br>Hot weekend + ice cream<br>Prefer 20% over 10%',
    eval: 'Lesson extracted'
  },
  {
    from: 22, to: 27,
    index: '06',
    title: 'The next similar case reads that memory',
    text: 'A similar scenario appears. This time xmemory returns the lesson before the agent acts. The model and prompt are unchanged; the available experience is not.',
    round: 'ROUND 2 · MEMORY READ',
    agent: 'Relevant lesson found<br><b>Reconsiders action</b>',
    memory: '<b>Retrieved</b><br>Hot weekend + ice cream<br>Prefer 20% over 10%',
    eval: 'Waiting for new action…'
  },
  {
    from: 27, to: 30.1,
    index: '07',
    title: 'The agent changes its behavior',
    text: 'It now chooses 20%. The evaluator confirms that 20% is optimal, regret falls to £0, and the lesson gains more evidence.',
    round: 'ROUND 2 · WARM MEMORY',
    agent: 'Memory changed behavior<br><b>Chooses 20% ✓</b>',
    memory: '<b>Lesson strengthened</b><br>Evidence: 2<br>Confidence ↑',
    eval: '<b>20% → optimal</b><br>Regret: £0'
  }
];

const ui = {
  round: document.getElementById('roundLabel'),
  index: document.getElementById('stepIndex'),
  title: document.getElementById('stepTitle'),
  text: document.getElementById('stepText'),
  agent: document.getElementById('agentChoice'),
  memory: document.getElementById('memoryState'),
  eval: document.getElementById('evalResult'),
  playPause: document.getElementById('playPause'),
  restart: document.getElementById('restart'),
  speed: document.getElementById('speed'),
  time: document.getElementById('timeLabel')
};

let playing = true;
let currentStep = -1;

function formatTime(seconds) {
  const s = Math.max(0, Math.min(30, Math.floor(seconds)));
  return `0:${String(s).padStart(2, '0')} / 0:30`;
}

function updateStep(seconds) {
  const i = steps.findIndex(step => seconds >= step.from && seconds < step.to);
  const next = i === -1 ? steps[steps.length - 1] : steps[i];
  if (i !== currentStep) {
    currentStep = i;
    ui.round.textContent = next.round;
    ui.index.textContent = next.index;
    ui.title.textContent = next.title;
    ui.text.textContent = next.text;
    ui.agent.innerHTML = next.agent;
    ui.memory.innerHTML = next.memory;
    ui.eval.innerHTML = next.eval;
  }
  ui.time.textContent = formatTime(seconds);
}

animation.addEventListener('enterFrame', event => {
  updateStep(event.currentTime / animation.frameRate);
});

animation.addEventListener('DOMLoaded', () => updateStep(0));

ui.playPause.addEventListener('click', () => {
  if (playing) {
    animation.pause();
    ui.playPause.textContent = 'Play';
  } else {
    animation.play();
    ui.playPause.textContent = 'Pause';
  }
  playing = !playing;
});

ui.restart.addEventListener('click', () => {
  animation.goToAndPlay(0, true);
  playing = true;
  ui.playPause.textContent = 'Pause';
  updateStep(0);
});

ui.speed.addEventListener('change', event => {
  animation.setSpeed(Number(event.target.value));
});
