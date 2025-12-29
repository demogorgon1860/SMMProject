"""
MAXIMUM STEALTH Bot + GoLogin
=======================================
Максимальная маскировка под человека.

Улучшения:
- Поведенческие "ошибки" (промахи, коррекции)
- Микро-движения и тремор руки
- Естественные паузы "на подумать"
- Случайные отвлечения
- Вариативность скорости в зависимости от "усталости"
- Имитация чтения контента
- Случайные "человеческие" действия
- Проверка timezone vs IP
- Session randomization

Использование:
    python stealth.py --profile ID urls.txt
"""

import asyncio
import random
import math
import os
import json
from datetime import datetime, timedelta
from typing import Optional, List, Dict, Tuple
from dataclasses import dataclass, field
import httpx
from playwright.async_api import async_playwright, Page, Browser, BrowserContext

try:
    from gologin import GoLogin
    GOLOGIN_SDK_AVAILABLE = True
except ImportError:
    GOLOGIN_SDK_AVAILABLE = False


# =============================================================================
# ПРОДВИНУТАЯ ИМИТАЦИЯ ЧЕЛОВЕКА
# =============================================================================

class HumanBehavior:
    """Продвинутая имитация человеческого поведения"""
    
    def __init__(self):
        # Состояние "усталости" (0-1)
        self.fatigue = 0.0
        self.session_start = datetime.now()
        self.actions_count = 0
        
        # Личность бота (генерируется случайно)
        self.personality = {
            'speed': random.uniform(0.7, 1.3),      # Скорость действий
            'accuracy': random.uniform(0.85, 0.98), # Точность кликов
            'patience': random.uniform(0.6, 1.4),   # Терпеливость (время просмотра)
            'curiosity': random.uniform(0.3, 0.8),  # Любопытство (случайные действия)
            'focus': random.uniform(0.7, 1.0),      # Концентрация
        }
        
        # История позиций мыши для естественности
        self.mouse_history: List[Tuple[int, int, float]] = []
        self.last_action_time = datetime.now()
    
    def update_fatigue(self):
        """Обновить уровень усталости"""
        session_duration = (datetime.now() - self.session_start).total_seconds() / 60
        
        # Усталость растёт со временем и количеством действий
        time_fatigue = min(session_duration / 60, 0.5)  # Max 0.5 за час
        action_fatigue = min(self.actions_count / 100, 0.3)  # Max 0.3 за 100 действий
        
        self.fatigue = min(time_fatigue + action_fatigue, 0.8)
        self.actions_count += 1
    
    def get_adjusted_delay(self, base_min: float, base_max: float) -> float:
        """Задержка с учётом усталости и личности"""
        # Базовая задержка
        mean = (base_min + base_max) / 2
        std = (base_max - base_min) / 4
        delay = random.gauss(mean, std)
        
        # Корректировка на личность
        delay *= self.personality['speed']
        
        # Усталость замедляет
        delay *= (1 + self.fatigue * 0.5)
        
        # Иногда очень длинные паузы (задумался)
        if random.random() < 0.05:
            delay *= random.uniform(2, 4)
        
        return max(base_min * 0.5, min(base_max * 2, delay))
    
    def should_make_mistake(self) -> bool:
        """Должен ли сделать ошибку?"""
        base_prob = 1 - self.personality['accuracy']
        fatigue_bonus = self.fatigue * 0.1
        return random.random() < (base_prob + fatigue_bonus)
    
    def should_get_distracted(self) -> bool:
        """Должен ли отвлечься?"""
        base_prob = self.personality['curiosity'] * 0.1
        fatigue_bonus = self.fatigue * 0.05
        return random.random() < (base_prob + fatigue_bonus)
    
    def get_watch_time_multiplier(self) -> float:
        """Множитель времени просмотра"""
        base = self.personality['patience']
        # Уставший смотрит меньше
        fatigue_penalty = self.fatigue * 0.3
        # Случайная вариация
        random_factor = random.uniform(0.8, 1.2)
        return max(0.5, base - fatigue_penalty) * random_factor


class AdvancedMouseSimulator:
    """Продвинутый симулятор движения мыши"""
    
    def __init__(self, page: Page, behavior: HumanBehavior):
        self.page = page
        self.behavior = behavior
        self.current_pos = (0, 0)
        self.velocity = (0, 0)
    
    def _perlin_noise(self, t: float, octaves: int = 3) -> float:
        """Шум Перлина для естественного тремора"""
        result = 0
        for i in range(octaves):
            freq = 2 ** i
            amp = 0.5 ** i
            result += math.sin(t * freq * 2 * math.pi) * amp
        return result
    
    def _generate_control_points(
        self, 
        start: Tuple[int, int], 
        end: Tuple[int, int],
        overshoot: bool = False
    ) -> List[Tuple[float, float]]:
        """Генерация контрольных точек с возможным промахом"""
        
        dx, dy = end[0] - start[0], end[1] - start[1]
        distance = math.sqrt(dx*dx + dy*dy)
        
        # Количество контрольных точек зависит от расстояния
        num_controls = max(2, int(distance / 100))
        
        points = [start]
        
        for i in range(num_controls):
            progress = (i + 1) / (num_controls + 1)
            
            # Базовая позиция
            base_x = start[0] + dx * progress
            base_y = start[1] + dy * progress
            
            # Отклонение (больше в середине пути)
            deviation = math.sin(progress * math.pi) * distance * 0.15
            angle = random.uniform(-math.pi/3, math.pi/3)
            
            ctrl_x = base_x + math.cos(angle) * deviation
            ctrl_y = base_y + math.sin(angle) * deviation
            
            points.append((ctrl_x, ctrl_y))
        
        # Если промах — добавляем точку за целью
        if overshoot:
            overshoot_dist = random.uniform(5, 25)
            angle = math.atan2(dy, dx)
            overshoot_point = (
                end[0] + math.cos(angle) * overshoot_dist,
                end[1] + math.sin(angle) * overshoot_dist
            )
            points.append(overshoot_point)
        
        points.append(end)
        return points
    
    def _catmull_rom_spline(
        self, 
        points: List[Tuple[float, float]], 
        num_samples: int
    ) -> List[Tuple[int, int]]:
        """Сплайн Катмулла-Рома для плавной кривой"""
        
        if len(points) < 2:
            return [(int(points[0][0]), int(points[0][1]))]
        
        # Добавляем граничные точки
        extended = [points[0]] + points + [points[-1]]
        
        result = []
        segments = len(extended) - 3
        
        for seg in range(segments):
            p0, p1, p2, p3 = extended[seg:seg+4]
            
            samples_per_segment = num_samples // segments
            
            for i in range(samples_per_segment):
                t = i / samples_per_segment
                t2, t3 = t*t, t*t*t
                
                x = 0.5 * (
                    2*p1[0] + (-p0[0]+p2[0])*t +
                    (2*p0[0]-5*p1[0]+4*p2[0]-p3[0])*t2 +
                    (-p0[0]+3*p1[0]-3*p2[0]+p3[0])*t3
                )
                y = 0.5 * (
                    2*p1[1] + (-p0[1]+p2[1])*t +
                    (2*p0[1]-5*p1[1]+4*p2[1]-p3[1])*t2 +
                    (-p0[1]+3*p1[1]-3*p2[1]+p3[1])*t3
                )
                
                result.append((int(x), int(y)))
        
        result.append((int(points[-1][0]), int(points[-1][1])))
        return result
    
    async def move_to(
        self, 
        target: Tuple[int, int],
        allow_overshoot: bool = True
    ):
        """Переместить мышь к цели с человеческим поведением"""
        
        start = self.current_pos
        distance = math.sqrt((target[0]-start[0])**2 + (target[1]-start[1])**2)
        
        if distance < 5:
            return
        
        # Решаем, будет ли промах
        overshoot = allow_overshoot and self.behavior.should_make_mistake() and distance > 50
        
        # Генерируем путь
        controls = self._generate_control_points(start, target, overshoot)
        num_points = max(15, int(distance / 5))
        path = self._catmull_rom_spline(controls, num_points)
        
        # Добавляем тремор и движемся
        time_offset = random.random() * 1000
        
        for i, (x, y) in enumerate(path):
            # Тремор руки (микро-движения)
            tremor_x = self._perlin_noise(time_offset + i * 0.1) * 2
            tremor_y = self._perlin_noise(time_offset + i * 0.1 + 100) * 2
            
            final_x = int(x + tremor_x)
            final_y = int(y + tremor_y)
            
            await self.page.mouse.move(final_x, final_y)
            
            # Переменная скорость (быстрее в середине, медленнее к концу)
            progress = i / len(path)
            speed_curve = math.sin(progress * math.pi)  # 0 -> 1 -> 0
            base_delay = 0.008
            delay = base_delay * (1 + (1 - speed_curve) * 0.5)
            
            # Иногда микро-паузы
            if random.random() < 0.02:
                delay += random.uniform(0.05, 0.15)
            
            await asyncio.sleep(delay)
            self.current_pos = (final_x, final_y)
        
        # Если был промах — корректируем
        if overshoot:
            await asyncio.sleep(random.uniform(0.1, 0.3))
            await self.move_to(target, allow_overshoot=False)
    
    async def click_at(
        self, 
        target: Tuple[int, int],
        click_variation: int = 5
    ):
        """Кликнуть с человеческими особенностями"""
        
        # Не кликаем точно в центр
        actual_target = (
            target[0] + random.randint(-click_variation, click_variation),
            target[1] + random.randint(-click_variation, click_variation)
        )
        
        await self.move_to(actual_target)
        
        # Пауза перед кликом (человек "целится")
        await asyncio.sleep(self.behavior.get_adjusted_delay(0.1, 0.4))
        
        # Иногда двигаем мышь во время клика (дрожание)
        if random.random() < 0.1:
            asyncio.create_task(self._micro_movement())
        
        await self.page.mouse.down()
        
        # Время удержания кнопки
        hold_time = random.uniform(0.05, 0.15)
        await asyncio.sleep(hold_time)
        
        await self.page.mouse.up()
    
    async def _micro_movement(self):
        """Микро-движение во время клика"""
        await asyncio.sleep(0.02)
        x, y = self.current_pos
        await self.page.mouse.move(
            x + random.randint(-2, 2),
            y + random.randint(-2, 2)
        )


class AdvancedScrollSimulator:
    """Продвинутый симулятор скролла"""
    
    def __init__(self, page: Page, behavior: HumanBehavior):
        self.page = page
        self.behavior = behavior
    
    async def scroll(
        self, 
        direction: str = 'down',
        amount: int = None,
        smooth: bool = True
    ):
        """Человеческий скролл"""
        
        if amount is None:
            amount = random.randint(150, 400)
        
        if direction == 'up':
            amount = -amount
        
        if not smooth:
            await self.page.mouse.wheel(0, amount)
            return
        
        # Разбиваем на шаги с ускорением/замедлением
        steps = random.randint(8, 15)
        
        # Генерируем профиль скорости (ease-in-out)
        velocities = []
        for i in range(steps):
            t = i / (steps - 1)
            # Синусоидальный ease-in-out
            velocity = math.sin(t * math.pi)
            velocities.append(velocity)
        
        # Нормализуем
        total = sum(velocities)
        step_amounts = [int(amount * v / total) for v in velocities]
        
        # Корректируем остаток
        diff = amount - sum(step_amounts)
        step_amounts[len(step_amounts)//2] += diff
        
        for step_amount in step_amounts:
            # Добавляем случайность
            actual = step_amount + random.randint(-3, 3)
            
            await self.page.mouse.wheel(0, actual)
            
            # Переменная задержка
            delay = random.uniform(0.02, 0.06)
            if random.random() < 0.1:
                delay += random.uniform(0.1, 0.3)  # Иногда пауза
            
            await asyncio.sleep(delay)
        
        # Инерция (небольшой дополнительный скролл)
        if random.random() < 0.3:
            await asyncio.sleep(random.uniform(0.1, 0.2))
            inertia = random.randint(10, 30) * (1 if amount > 0 else -1)
            await self.page.mouse.wheel(0, inertia)


# =============================================================================
# СЛУЧАЙНЫЕ ЧЕЛОВЕЧЕСКИЕ ДЕЙСТВИЯ
# =============================================================================

class HumanActions:
    """Случайные человеческие действия для маскировки"""
    
    def __init__(self, page: Page, mouse: AdvancedMouseSimulator, behavior: HumanBehavior):
        self.page = page
        self.mouse = mouse
        self.behavior = behavior
    
    async def idle_movement(self):
        """Случайное движение мыши когда 'думаем'"""
        viewport = await self.page.evaluate('() => ({w: window.innerWidth, h: window.innerHeight})')
        
        # Небольшое случайное движение
        current = self.mouse.current_pos
        target = (
            current[0] + random.randint(-100, 100),
            current[1] + random.randint(-50, 50)
        )
        
        # Ограничиваем viewport
        target = (
            max(50, min(viewport['w'] - 50, target[0])),
            max(50, min(viewport['h'] - 50, target[1]))
        )
        
        await self.mouse.move_to(target)
    
    async def read_content(self, duration: float = None):
        """Имитация чтения контента"""
        if duration is None:
            duration = self.behavior.get_adjusted_delay(2, 5)
        
        elapsed = 0
        while elapsed < duration:
            # Иногда двигаем мышь как будто следим за текстом
            if random.random() < 0.3:
                await self.idle_movement()
            
            wait = random.uniform(0.5, 1.5)
            await asyncio.sleep(wait)
            elapsed += wait
    
    async def look_around(self):
        """Осмотреться на странице"""
        viewport = await self.page.evaluate('() => ({w: window.innerWidth, h: window.innerHeight})')
        
        # 2-4 случайных взгляда
        for _ in range(random.randint(2, 4)):
            target = (
                random.randint(100, viewport['w'] - 100),
                random.randint(100, viewport['h'] - 100)
            )
            await self.mouse.move_to(target)
            await asyncio.sleep(random.uniform(0.3, 0.8))
    
    async def hesitate(self):
        """Заколебаться перед действием"""
        # Движение к цели и обратно
        current = self.mouse.current_pos
        
        # Немного подвинуться
        offset = (
            current[0] + random.randint(-30, 30),
            current[1] + random.randint(-20, 20)
        )
        await self.mouse.move_to(offset)
        await asyncio.sleep(random.uniform(0.2, 0.5))
    
    async def check_something_else(self):
        """Проверить что-то другое (отвлечься)"""
        # Скролл вверх чтобы посмотреть что-то
        scroller = AdvancedScrollSimulator(self.page, self.behavior)
        
        if random.random() < 0.5:
            await scroller.scroll('up', random.randint(100, 300))
            await asyncio.sleep(random.uniform(1, 3))
            await scroller.scroll('down', random.randint(100, 300))
        else:
            # Или просто посмотреть в сторону
            await self.look_around()
    
    async def maybe_distraction(self):
        """Может отвлечься на что-то"""
        if self.behavior.should_get_distracted():
            action = random.choice([
                self.idle_movement,
                self.look_around,
                self.check_something_else,
            ])
            await action()
            return True
        return False


# =============================================================================
# GoLogin API Client
# =============================================================================

class GoLoginAPI:
    """GoLogin REST API клиент"""
    
    BASE_URL = "https://api.gologin.com"
    CLOUD_BROWSER_URL = "wss://cloudbrowser.gologin.com/connect"
    
    def __init__(self, token: str):
        self.token = token
        self.headers = {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json"
        }
    
    async def _request(self, method: str, endpoint: str, data: dict = None) -> dict:
        async with httpx.AsyncClient(timeout=30.0) as client:
            url = f"{self.BASE_URL}{endpoint}"
            
            if method == "GET":
                response = await client.get(url, headers=self.headers, params=data)
            elif method == "POST":
                response = await client.post(url, headers=self.headers, json=data)
            elif method == "PATCH":
                response = await client.patch(url, headers=self.headers, json=data)
            elif method == "DELETE":
                response = await client.delete(url, headers=self.headers)
            else:
                raise ValueError(f"Unknown method: {method}")
            
            if response.status_code >= 400:
                raise Exception(f"GoLogin API error {response.status_code}: {response.text}")
            
            return response.json() if response.text else {}
    
    async def get_profiles(self, limit: int = 100) -> List[Dict]:
        result = await self._request("GET", "/browser/v2", {"limit": limit})
        return result.get("profiles", [])
    
    async def get_profile(self, profile_id: str) -> Dict:
        return await self._request("GET", f"/browser/{profile_id}")
    
    async def create_profile(self, name: str, os: str = "win", proxy: Dict = None) -> Dict:
        fingerprint = await self._request("GET", f"/browser/fingerprint?os={os}")
        
        data = {
            "name": name,
            "os": os,
            "navigator": fingerprint.get("navigator", {}),
            "proxyEnabled": proxy is not None,
            "proxy": proxy or {"mode": "none"},
            "webRTC": {"mode": "alerted", "enabled": True},
            "canvas": {"mode": "noise"},
            "webGL": {"mode": "noise"},
            "timezone": {"enabled": True, "fillBasedOnIp": True},
            "geolocation": {"mode": "prompt", "enabled": True}
        }
        
        return await self._request("POST", "/browser", data)
    
    def get_cloud_browser_url(self, profile_id: str = None) -> str:
        url = f"{self.CLOUD_BROWSER_URL}?token={self.token}"
        if profile_id:
            url += f"&profile={profile_id}"
        return url


# =============================================================================
# Конфигурация
# =============================================================================

@dataclass
class StealthConfig:
    """Настройки максимальной маскировки"""
    
    # GoLogin
    token: str = field(default_factory=lambda: os.getenv("GOLOGIN_TOKEN", ""))
    profile_id: Optional[str] = None
    mode: str = "local"  # local, cloud
    
    # Лимиты сессии (для естественности)
    max_session_duration_minutes: int = 45  # Макс. длина сессии
    max_actions_per_session: int = 50       # Макс. действий за сессию
    
    # Время активности (имитация реального человека)
    active_hours_start: int = 9   # Начало активности
    active_hours_end: int = 23    # Конец активности
    
    # 
    reel_watch_min: int = 5
    reel_watch_max: int = 30
    post_watch_min: int = 2
    post_watch_max: int = 10
    
    auto_like: bool = True
    like_probability: float = 0.7  # Не лайкаем всё подряд!
    auto_follow: bool = False
    follow_probability: float = 0.1
    
    # Файлы
    log_file: str = "./stealth.log"
    screenshots_dir: str = "./screenshots"
    state_file: str = "./bot_state.json"  # Сохранение состояния


# =============================================================================
# Основной Stealth Bot
# =============================================================================

class StealthBot:
    """ Бот с максимальной маскировкой"""
    
    def __init__(self, config: StealthConfig = None):
        self.config = config or StealthConfig()
        
        if not self.config.token:
            raise ValueError("GoLogin токен не найден!")
        
        self.api = GoLoginAPI(self.config.token)
        self.gologin_sdk = None
        
        self.playwright = None
        self.browser: Optional[Browser] = None
        self.context: Optional[BrowserContext] = None
        self.page: Optional[Page] = None
        
        # Человеческое поведение
        self.behavior = HumanBehavior()
        self.mouse: Optional[AdvancedMouseSimulator] = None
        self.scroller: Optional[AdvancedScrollSimulator] = None
        self.human_actions: Optional[HumanActions] = None
        
        self.viewport = {'width': 1280, 'height': 800}
        
        self.stats = {
            'total': 0,
            'likes': 0,
            'follows': 0,
            'errors': 0,
            'session_start': None
        }
        
        os.makedirs(self.config.screenshots_dir, exist_ok=True)
        
        # Загружаем состояние
        self._load_state()
    
    def _load_state(self):
        """Загрузить сохранённое состояние"""
        if os.path.exists(self.config.state_file):
            try:
                with open(self.config.state_file, 'r') as f:
                    state = json.load(f)
                    # Восстанавливаем личность (для консистентности)
                    if 'personality' in state:
                        self.behavior.personality = state['personality']
            except:
                pass
    
    def _save_state(self):
        """Сохранить состояние"""
        state = {
            'personality': self.behavior.personality,
            'last_session': datetime.now().isoformat(),
            'stats': self.stats
        }
        with open(self.config.state_file, 'w') as f:
            json.dump(state, f, indent=2)
    
    def log(self, msg: str):
        ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        line = f"[{ts}] {msg}"
        print(line)
        with open(self.config.log_file, 'a', encoding='utf-8') as f:
            f.write(line + '\n')
    
    def _check_session_limits(self) -> bool:
        """Проверить лимиты сессии"""
        if self.stats['session_start']:
            duration = (datetime.now() - self.stats['session_start']).total_seconds() / 60
            if duration >= self.config.max_session_duration_minutes:
                self.log(f"⏰ Достигнут лимит времени сессии ({duration:.0f} мин)")
                return False
        
        if self.stats['total'] >= self.config.max_actions_per_session:
            self.log(f"⏰ Достигнут лимит действий ({self.stats['total']})")
            return False
        
        return True
    
    def _is_active_hours(self) -> bool:
        """Проверить, активные ли часы"""
        hour = datetime.now().hour
        return self.config.active_hours_start <= hour < self.config.active_hours_end
    
    async def start(self):
        """Запуск"""
        self.playwright = await async_playwright().start()
        
        profile_id = self.config.profile_id
        
        if self.config.mode == "cloud":
            ws_url = self.api.get_cloud_browser_url(profile_id)
            self.log(f"☁️ Облачный браузер...")
            self.browser = await self.playwright.chromium.connect_over_cdp(ws_url)
        elif GOLOGIN_SDK_AVAILABLE:
            self.log(f"🖥️ Локальный профиль...")
            self.gologin_sdk = GoLogin({
                "token": self.config.token,
                "profile_id": profile_id,
            })
            debugger_address = self.gologin_sdk.start()
            self.browser = await self.playwright.chromium.connect_over_cdp(f"http://{debugger_address}")
        else:
            ws_url = self.api.get_cloud_browser_url(profile_id)
            self.browser = await self.playwright.chromium.connect_over_cdp(ws_url)
        
        contexts = self.browser.contexts
        self.context = contexts[0] if contexts else await self.browser.new_context()
        self.page = self.context.pages[0] if self.context.pages else await self.context.new_page()
        
        # Инициализируем симуляторы
        self.mouse = AdvancedMouseSimulator(self.page, self.behavior)
        self.scroller = AdvancedScrollSimulator(self.page, self.behavior)
        self.human_actions = HumanActions(self.page, self.mouse, self.behavior)
        
        try:
            vp = await self.page.evaluate('() => ({width: window.innerWidth, height: window.innerHeight})')
            self.viewport = vp
            self.mouse.current_pos = (vp['width'] // 2, vp['height'] // 2)
        except:
            pass
        
        self.stats['session_start'] = datetime.now()
        self.log(f"✓ Браузер запущен")
        self.log(f"   Личность: speed={self.behavior.personality['speed']:.2f}, "
                f"accuracy={self.behavior.personality['accuracy']:.2f}")
    
    async def stop(self):
        """Остановка"""
        self._save_state()
        
        if self.gologin_sdk:
            self.gologin_sdk.stop()
        
        if self.browser:
            await self.browser.close()
        
        if self.playwright:
            await self.playwright.stop()
        
        self.log("✓ Браузер остановлен")
    
    async def dismiss_popups(self):
        """Закрыть попапы"""
        selectors = [
            'button:has-text("Not Now")',
            'button:has-text("Не сейчас")',
            '[aria-label="Close"]',
        ]
        
        for sel in selectors:
            try:
                btn = await self.page.query_selector(sel)
                if btn and await btn.is_visible():
                    box = await btn.bounding_box()
                    if box:
                        await self.mouse.click_at((
                            int(box['x'] + box['width']/2),
                            int(box['y'] + box['height']/2)
                        ))
                        await asyncio.sleep(0.5)
            except:
                pass
    
    async def like_content(self) -> bool:
        """Лайкнуть с человеческим поведением"""
        
        # Не лайкаем всё подряд
        if random.random() > self.config.like_probability:
            self.log("  💭 Не буду лайкать это")
            return False
        
        # Иногда колеблемся
        if random.random() < 0.2:
            await self.human_actions.hesitate()
        
        # Двойной тап или кнопка
        if random.random() < 0.6:
            self.log("  ❤️ Двойной тап")
            center = (self.viewport['width'] // 2, self.viewport['height'] // 2)
            target = (
                center[0] + random.randint(-80, 80),
                center[1] + random.randint(-80, 80)
            )
            
            await self.mouse.click_at(target)
            await asyncio.sleep(random.uniform(0.08, 0.15))
            await self.mouse.click_at((
                target[0] + random.randint(-5, 5),
                target[1] + random.randint(-5, 5)
            ))
            
            await asyncio.sleep(0.5)
            return True
        
        # Кнопка лайка
        like_selectors = [
            'svg[aria-label="Like"]',
            'svg[aria-label="Нравится"]',
        ]
        
        for sel in like_selectors:
            try:
                btn = await self.page.query_selector(sel)
                if btn:
                    box = await btn.bounding_box()
                    if box:
                        self.log("  ❤️ Кнопка лайка")
                        await self.mouse.click_at((
                            int(box['x'] + box['width']/2),
                            int(box['y'] + box['height']/2)
                        ))
                        return True
            except:
                continue
        
        return False
    
    async def watch_content(self, base_min: float, base_max: float):
        """Смотреть контент с человеческим поведением"""
        
        # Время с учётом личности
        multiplier = self.behavior.get_watch_time_multiplier()
        watch_time = self.behavior.get_adjusted_delay(base_min, base_max) * multiplier
        
        self.log(f"  👀 Смотрю {watch_time:.1f} сек")
        
        elapsed = 0
        while elapsed < watch_time:
            # Обновляем усталость
            self.behavior.update_fatigue()
            
            # Сколько ждать до следующего "события"
            wait = random.uniform(2, 5)
            await asyncio.sleep(min(wait, watch_time - elapsed))
            elapsed += wait
            
            # Случайные действия во время просмотра
            action_roll = random.random()
            
            if action_roll < 0.1:
                # Отвлеклись
                await self.human_actions.maybe_distraction()
            elif action_roll < 0.2:
                # Подвигали мышь
                await self.human_actions.idle_movement()
            elif action_roll < 0.25:
                # Поскроллили немного
                await self.scroller.scroll('down', random.randint(50, 150))
    
    async def process_reel(self, url: str) -> bool:
        """Обработать Reels"""
        
        if not self._check_session_limits():
            return False
        
        self.stats['total'] += 1
        
        try:
            self.log(f"\n{'='*50}")
            self.log(f"🎬 Reels #{self.stats['total']} (усталость: {self.behavior.fatigue:.0%})")
            self.log(f"   {url}")
            
            await self.page.goto(url, wait_until='domcontentloaded', timeout=30000)
            
            # Ждём загрузки (человек не сразу начинает)
            await asyncio.sleep(self.behavior.get_adjusted_delay(1.5, 3))
            await self.dismiss_popups()
            
            # Осматриваемся
            if random.random() < 0.3:
                await self.human_actions.look_around()
            
            # Смотрим
            await self.watch_content(self.config.reel_watch_min, self.config.reel_watch_max)
            
            # Лайк
            if self.config.auto_like:
                if await self.like_content():
                    self.stats['likes'] += 1
            
            # Пауза перед уходом
            await asyncio.sleep(self.behavior.get_adjusted_delay(0.5, 2))
            
            self.log("  ✅ Готово!")
            return True
            
        except Exception as e:
            self.stats['errors'] += 1
            self.log(f"  ❌ Ошибка: {e}")
            return False
    
    async def process_post(self, url: str) -> bool:
        """Обработать пост"""
        
        if not self._check_session_limits():
            return False
        
        self.stats['total'] += 1
        
        try:
            self.log(f"\n{'='*50}")
            self.log(f"📷 Пост #{self.stats['total']}")
            self.log(f"   {url}")
            
            await self.page.goto(url, wait_until='domcontentloaded', timeout=30000)
            await asyncio.sleep(self.behavior.get_adjusted_delay(1, 2.5))
            await self.dismiss_popups()
            
            # Читаем/смотрим
            await self.watch_content(self.config.post_watch_min, self.config.post_watch_max)
            
            # Иногда скроллим к комментариям
            if random.random() < 0.3:
                await self.scroller.scroll('down', random.randint(200, 400))
                await self.human_actions.read_content(random.uniform(1, 3))
            
            # Лайк
            if self.config.auto_like:
                if await self.like_content():
                    self.stats['likes'] += 1
            
            self.log("  ✅ Готово!")
            return True
            
        except Exception as e:
            self.stats['errors'] += 1
            self.log(f"  ❌ Ошибка: {e}")
            return False
    
    async def process_url(self, url: str) -> bool:
        """Обработать URL"""
        url = url.strip()
        
        if '/reel/' in url:
            return await self.process_reel(url)
        elif '/p/' in url:
            return await self.process_post(url)
        else:
            self.log(f"⚠️ Неизвестный URL: {url}")
            return False
    
    async def process_urls(self, urls: list[str]):
        """Обработать список URL"""
        self.log(f"\n🚀 Начинаю обработку {len(urls)} URL")
        self.log(f"   Макс. время сессии: {self.config.max_session_duration_minutes} мин")
        self.log(f"   Макс. действий: {self.config.max_actions_per_session}")
        
        for i, url in enumerate(urls):
            # Проверяем лимиты
            if not self._check_session_limits():
                self.log(f"\n⏰ Сессия завершена (обработано {i}/{len(urls)})")
                break
            
            await self.process_url(url)
            
            if i < len(urls) - 1:
                # Пауза между URL (с учётом усталости)
                base_delay = random.uniform(15, 45)
                delay = base_delay * (1 + self.behavior.fatigue * 0.5)
                
                # Иногда длинная пауза (перерыв)
                if random.random() < 0.1:
                    delay *= random.uniform(2, 4)
                    self.log(f"  ☕ Перерыв {delay:.0f} сек...")
                else:
                    self.log(f"  ⏳ Пауза {delay:.0f} сек...")
                
                await asyncio.sleep(delay)
        
        self._print_stats()
    
    def _print_stats(self):
        self.log(f"\n{'='*50}")
        self.log(f"📊 ИТОГИ СЕССИИ:")
        self.log(f"   Обработано: {self.stats['total']}")
        self.log(f"   Лайков: {self.stats['likes']}")
        self.log(f"   Ошибок: {self.stats['errors']}")
        self.log(f"   Усталость: {self.behavior.fatigue:.0%}")
        if self.stats['session_start']:
            duration = datetime.now() - self.stats['session_start']
            self.log(f"   Время: {duration}")


# =============================================================================
# CLI
# =============================================================================

async def main():
    import sys
    
    print("""
╔════════════════════════════════════════════════════════════════╗
║        🥷 STEALTH Bot                                 ║
╠════════════════════════════════════════════════════════════════╣
║  Максимальная маскировка под человека:                         ║
║  • Промахи и коррекции курсора                                 ║
║  • Микро-тремор руки                                           ║
║  • Имитация усталости                                          ║
║  • Случайные отвлечения                                        ║
║  • Лимиты сессий                                               ║
║  • Сохранение "личности" между сессиями                        ║
╠════════════════════════════════════════════════════════════════╣
║  python stealth.py --profile ID urls.txt             ║
║  python stealth.py --cloud --profile ID urls.txt     ║
╚════════════════════════════════════════════════════════════════╝
    """)
    
    config = StealthConfig()
    urls_file = None
    
    args = sys.argv[1:]
    i = 0
    while i < len(args):
        arg = args[i]
        
        if arg == '--token' and i + 1 < len(args):
            config.token = args[i + 1]
            i += 2
        elif arg == '--profile' and i + 1 < len(args):
            config.profile_id = args[i + 1]
            i += 2
        elif arg == '--cloud':
            config.mode = "cloud"
            i += 1
        elif arg == '--max-time' and i + 1 < len(args):
            config.max_session_duration_minutes = int(args[i + 1])
            i += 2
        elif arg == '--max-actions' and i + 1 < len(args):
            config.max_actions_per_session = int(args[i + 1])
            i += 2
        elif arg == '--like-prob' and i + 1 < len(args):
            config.like_probability = float(args[i + 1])
            i += 2
        elif not arg.startswith('--') and os.path.exists(arg):
            urls_file = arg
            i += 1
        else:
            i += 1
    
    if not config.token:
        print("❌ Токен не найден! export GOLOGIN_TOKEN=...")
        return
    
    bot = StealthBot(config)
    
    try:
        await bot.start()
        
        if urls_file:
            with open(urls_file, 'r') as f:
                urls = [l.strip() for l in f if l.strip() and 'instagram' in l.lower()]
            
            if urls:
                await bot.process_urls(urls)
            else:
                print("❌ Нет URL в файле")
        else:
            print("🔹 Открываю Instagram...")
            await bot.page.goto("https://www.instagram.com")
            input("Enter для выхода...")
    
    finally:
        await bot.stop()


if __name__ == "__main__":
    asyncio.run(main())
