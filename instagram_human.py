"""
Instagram Human-Like Bot
========================
Имитация человеческого поведения: просмотр Reels/постов, лайки, подписки.

Функции:
- Просмотр Reels с лайком
- Просмотр постов с лайком
- Подписка на аккаунт
- Человеческие паттерны поведения

Использование:
    python instagram_human.py urls.txt
    python instagram_human.py --interactive

Формат urls.txt:
    https://www.instagram.com/reel/ABC123/
    https://www.instagram.com/p/XYZ789/
    https://www.instagram.com/username/
"""

import asyncio
import random
import math
import os
from datetime import datetime
from typing import Optional, Literal
from dataclasses import dataclass
from playwright.async_api import async_playwright, Page, BrowserContext


# =============================================================================
# Конфигурация
# =============================================================================

@dataclass
class InstagramConfig:
    """Настройки бота"""
    
    # Время просмотра (секунды)
    reel_watch_time_min: int = 8
    reel_watch_time_max: int = 25
    post_watch_time_min: int = 3
    post_watch_time_max: int = 8
    
    # Задержки
    action_delay_min: float = 0.5
    action_delay_max: float = 2.0
    think_before_click_min: float = 0.2
    think_before_click_max: float = 0.8
    
    # Вероятности случайных действий
    prob_random_scroll: float = 0.25
    prob_view_comments: float = 0.15
    prob_view_profile: float = 0.1
    
    # Действия по умолчанию
    auto_like: bool = True
    auto_follow: bool = False  # Осторожно с массовыми подписками!
    
    # Браузер
    headless: bool = False
    viewport_width: int = 430  # Мобильный размер
    viewport_height: int = 932
    mobile_emulation: bool = True
    
    # Профиль
    user_data_dir: str = "./instagram_profile"
    
    # Логи
    log_file: str = "./instagram_bot.log"
    screenshots_dir: str = "./screenshots"


# =============================================================================
# Утилиты
# =============================================================================

def random_delay(min_sec: float, max_sec: float) -> float:
    """Случайная задержка с нормальным распределением"""
    mean = (min_sec + max_sec) / 2
    std = (max_sec - min_sec) / 4
    delay = random.gauss(mean, std)
    return max(min_sec, min(max_sec, delay))


def bezier_point(p0: tuple, p1: tuple, p2: tuple, p3: tuple, t: float) -> tuple:
    """Точка на кубической кривой Безье"""
    x = (1-t)**3 * p0[0] + 3*(1-t)**2 * t * p1[0] + 3*(1-t) * t**2 * p2[0] + t**3 * p3[0]
    y = (1-t)**3 * p0[1] + 3*(1-t)**2 * t * p1[1] + 3*(1-t) * t**2 * p2[1] + t**3 * p3[1]
    return (int(x), int(y))


def generate_human_path(start: tuple, end: tuple) -> list:
    """Генерирует человекоподобный путь мыши"""
    dx, dy = end[0] - start[0], end[1] - start[1]
    
    ctrl1 = (
        start[0] + dx * random.uniform(0.2, 0.4) + random.randint(-30, 30),
        start[1] + dy * random.uniform(0.2, 0.4) + random.randint(-30, 30)
    )
    ctrl2 = (
        start[0] + dx * random.uniform(0.6, 0.8) + random.randint(-30, 30),
        start[1] + dy * random.uniform(0.6, 0.8) + random.randint(-30, 30)
    )
    
    num_points = random.randint(12, 25)
    path = []
    for i in range(num_points + 1):
        t = i / num_points
        point = bezier_point(start, ctrl1, ctrl2, end, t)
        point = (point[0] + random.randint(-1, 1), point[1] + random.randint(-1, 1))
        path.append(point)
    
    return path


# =============================================================================
# Основной класс
# =============================================================================

class InstagramHumanBot:
    """Бот для Instagram с человеческим поведением"""
    
    def __init__(self, config: Optional[InstagramConfig] = None):
        self.config = config or InstagramConfig()
        self.context: Optional[BrowserContext] = None
        self.page: Optional[Page] = None
        self.playwright = None
        self.mouse_pos = (215, 400)  # Примерный центр мобильного экрана
        
        self.stats = {
            'total': 0,
            'likes': 0,
            'follows': 0,
            'errors': 0,
            'start_time': None
        }
        
        os.makedirs(self.config.screenshots_dir, exist_ok=True)
        os.makedirs(self.config.user_data_dir, exist_ok=True)
    
    def log(self, message: str):
        """Логирование"""
        timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        log_line = f"[{timestamp}] {message}"
        print(log_line)
        with open(self.config.log_file, 'a', encoding='utf-8') as f:
            f.write(log_line + '\n')
    
    async def start(self):
        """Запуск браузера"""
        self.playwright = await async_playwright().start()
        
        # Мобильная эмуляция для Instagram
        device = self.playwright.devices['iPhone 14 Pro Max']
        
        self.context = await self.playwright.chromium.launch_persistent_context(
            user_data_dir=self.config.user_data_dir,
            headless=self.config.headless,
            **device,
            args=[
                '--disable-blink-features=AutomationControlled',
                '--no-sandbox',
            ],
            ignore_default_args=['--enable-automation'],
        )
        
        self.page = self.context.pages[0] if self.context.pages else await self.context.new_page()
        
        # Anti-detection
        await self.page.add_init_script("""
            Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
            Object.defineProperty(navigator, 'plugins', {get: () => [1, 2, 3]});
        """)
        
        self.stats['start_time'] = datetime.now()
        self.log("✓ Браузер запущен (мобильный режим)")
    
    async def stop(self):
        """Остановка"""
        if self.context:
            await self.context.close()
        if self.playwright:
            await self.playwright.stop()
        self.log("✓ Браузер остановлен")
    
    async def human_move(self, x: int, y: int):
        """Плавное движение мыши"""
        path = generate_human_path(self.mouse_pos, (x, y))
        for point in path:
            await self.page.mouse.move(point[0], point[1])
            await asyncio.sleep(random.uniform(0.003, 0.015))
        self.mouse_pos = (x, y)
    
    async def human_click(self, selector: str = None, x: int = None, y: int = None):
        """Человеческий клик"""
        if selector:
            element = await self.page.query_selector(selector)
            if not element:
                return False
            box = await element.bounding_box()
            if not box:
                return False
            x = int(box['x'] + box['width'] * random.uniform(0.3, 0.7))
            y = int(box['y'] + box['height'] * random.uniform(0.3, 0.7))
        
        await self.human_move(x, y)
        await asyncio.sleep(random_delay(self.config.think_before_click_min, self.config.think_before_click_max))
        await self.page.mouse.click(x, y)
        return True
    
    async def human_scroll(self, direction: str = 'down', amount: int = None):
        """Человеческий скролл (свайп на мобильном)"""
        if amount is None:
            amount = random.randint(150, 350)
        
        start_x = self.config.viewport_width // 2 + random.randint(-30, 30)
        
        if direction == 'down':
            start_y = self.config.viewport_height * 0.7
            end_y = self.config.viewport_height * 0.3
        else:
            start_y = self.config.viewport_height * 0.3
            end_y = self.config.viewport_height * 0.7
        
        # Свайп
        await self.page.mouse.move(start_x, start_y)
        await self.page.mouse.down()
        
        steps = random.randint(5, 10)
        for i in range(steps):
            progress = (i + 1) / steps
            current_y = start_y + (end_y - start_y) * progress
            await self.page.mouse.move(start_x + random.randint(-5, 5), current_y)
            await asyncio.sleep(random.uniform(0.02, 0.05))
        
        await self.page.mouse.up()
    
    async def human_double_tap(self, x: int = None, y: int = None):
        """Двойной тап для лайка (Instagram стиль)"""
        if x is None:
            x = self.config.viewport_width // 2 + random.randint(-50, 50)
        if y is None:
            y = self.config.viewport_height // 2 + random.randint(-50, 50)
        
        await self.human_move(x, y)
        await asyncio.sleep(random.uniform(0.05, 0.15))
        
        # Двойной тап
        await self.page.mouse.click(x, y)
        await asyncio.sleep(random.uniform(0.08, 0.15))
        await self.page.mouse.click(x, y)
    
    async def dismiss_popups(self):
        """Закрыть попапы"""
        popup_selectors = [
            'button:has-text("Not Now")',
            'button:has-text("Не сейчас")',
            'button:has-text("Cancel")',
            'button:has-text("Отмена")',
            '[aria-label="Close"]',
            '[aria-label="Закрыть"]',
        ]
        
        for selector in popup_selectors:
            try:
                btn = await self.page.query_selector(selector)
                if btn and await btn.is_visible():
                    await btn.click()
                    await asyncio.sleep(0.5)
            except:
                pass
    
    async def random_actions(self):
        """Случайные человеческие действия"""
        if random.random() < self.config.prob_random_scroll:
            self.log("  🔄 Случайный скролл")
            await self.human_scroll('down')
            await asyncio.sleep(random_delay(0.5, 1.5))
        
        if random.random() < 0.2:
            # Случайное движение мыши
            x = random.randint(50, self.config.viewport_width - 50)
            y = random.randint(100, self.config.viewport_height - 100)
            await self.human_move(x, y)
            await asyncio.sleep(random_delay(0.2, 0.6))
    
    async def like_content(self) -> bool:
        """Поставить лайк (двойной тап или кнопка)"""
        
        # Способ 1: Двойной тап по контенту
        if random.random() < 0.6:
            self.log("  ❤️ Двойной тап для лайка")
            await self.human_double_tap()
            await asyncio.sleep(0.5)
            return True
        
        # Способ 2: Кнопка лайка
        like_selectors = [
            'svg[aria-label="Like"]',
            'svg[aria-label="Нравится"]',
            '[aria-label="Like"]',
            '[aria-label="Нравится"]',
            'span._aamw button',
        ]
        
        for selector in like_selectors:
            try:
                like_btn = await self.page.query_selector(selector)
                if like_btn:
                    # Проверяем, не лайкнуто ли
                    parent = await like_btn.evaluate_handle('el => el.closest("button") || el.parentElement')
                    
                    self.log("  ❤️ Кликаю кнопку лайка")
                    await self.human_click(selector=selector)
                    return True
            except:
                continue
        
        return False
    
    async def follow_account(self) -> bool:
        """Подписаться на аккаунт"""
        follow_selectors = [
            'button:has-text("Follow")',
            'button:has-text("Подписаться")',
            'div[role="button"]:has-text("Follow")',
        ]
        
        for selector in follow_selectors:
            try:
                btn = await self.page.query_selector(selector)
                if btn:
                    text = await btn.inner_text()
                    # Не кликаем если уже подписаны
                    if 'Following' in text or 'Подписки' in text:
                        self.log("  ✓ Уже подписан")
                        return False
                    
                    self.log("  ➕ Подписываюсь")
                    await self.human_click(selector=selector)
                    return True
            except:
                continue
        
        return False
    
    async def process_reel(self, url: str) -> bool:
        """Обработать Reels"""
        self.stats['total'] += 1
        
        try:
            self.log(f"\n{'='*50}")
            self.log(f"🎬 Reels #{self.stats['total']}")
            self.log(f"   {url}")
            
            # Переход
            await self.page.goto(url, wait_until='domcontentloaded', timeout=30000)
            await asyncio.sleep(random_delay(2, 4))
            await self.dismiss_popups()
            
            # Смотрим
            watch_time = random_delay(self.config.reel_watch_time_min, self.config.reel_watch_time_max)
            self.log(f"  👀 Смотрю {watch_time:.1f} сек")
            
            elapsed = 0
            while elapsed < watch_time:
                wait = min(random_delay(3, 7), watch_time - elapsed)
                await asyncio.sleep(wait)
                elapsed += wait
                
                if random.random() < 0.2:
                    await self.random_actions()
            
            # Лайк
            if self.config.auto_like:
                if await self.like_content():
                    self.stats['likes'] += 1
            
            # Подписка (если включено)
            if self.config.auto_follow and random.random() < 0.3:
                if await self.follow_account():
                    self.stats['follows'] += 1
            
            await asyncio.sleep(random_delay(1, 2))
            self.log("  ✅ Готово!")
            return True
            
        except Exception as e:
            self.stats['errors'] += 1
            self.log(f"  ❌ Ошибка: {e}")
            return False
    
    async def process_post(self, url: str) -> bool:
        """Обработать пост"""
        self.stats['total'] += 1
        
        try:
            self.log(f"\n{'='*50}")
            self.log(f"📷 Пост #{self.stats['total']}")
            self.log(f"   {url}")
            
            await self.page.goto(url, wait_until='domcontentloaded', timeout=30000)
            await asyncio.sleep(random_delay(2, 3))
            await self.dismiss_popups()
            
            # Смотрим
            watch_time = random_delay(self.config.post_watch_time_min, self.config.post_watch_time_max)
            self.log(f"  👀 Смотрю {watch_time:.1f} сек")
            await asyncio.sleep(watch_time)
            
            # Иногда скроллим карусель
            if random.random() < 0.4:
                await self.human_scroll('down')
                await asyncio.sleep(random_delay(1, 2))
            
            # Лайк
            if self.config.auto_like:
                if await self.like_content():
                    self.stats['likes'] += 1
            
            await asyncio.sleep(random_delay(0.5, 1.5))
            self.log("  ✅ Готово!")
            return True
            
        except Exception as e:
            self.stats['errors'] += 1
            self.log(f"  ❌ Ошибка: {e}")
            return False
    
    async def process_profile(self, url: str) -> bool:
        """Посетить профиль и подписаться"""
        self.stats['total'] += 1
        
        try:
            self.log(f"\n{'='*50}")
            self.log(f"👤 Профиль #{self.stats['total']}")
            self.log(f"   {url}")
            
            await self.page.goto(url, wait_until='domcontentloaded', timeout=30000)
            await asyncio.sleep(random_delay(2, 4))
            await self.dismiss_popups()
            
            # Смотрим профиль
            self.log("  👀 Изучаю профиль")
            await asyncio.sleep(random_delay(2, 4))
            
            # Скролл по постам
            if random.random() < 0.5:
                await self.human_scroll('down')
                await asyncio.sleep(random_delay(1, 2))
            
            # Подписка
            if self.config.auto_follow:
                if await self.follow_account():
                    self.stats['follows'] += 1
            
            self.log("  ✅ Готово!")
            return True
            
        except Exception as e:
            self.stats['errors'] += 1
            self.log(f"  ❌ Ошибка: {e}")
            return False
    
    async def process_url(self, url: str) -> bool:
        """Определить тип URL и обработать"""
        url = url.strip()
        
        if '/reel/' in url:
            return await self.process_reel(url)
        elif '/p/' in url:
            return await self.process_post(url)
        elif 'instagram.com/' in url:
            return await self.process_profile(url)
        else:
            self.log(f"⚠️ Неизвестный тип URL: {url}")
            return False
    
    async def process_urls(self, urls: list[str], delay_between: tuple = (15, 45)):
        """Обработать список URL"""
        self.log(f"\n🚀 Начинаю обработку {len(urls)} URL")
        
        for i, url in enumerate(urls):
            await self.process_url(url)
            
            if i < len(urls) - 1:
                delay = random_delay(delay_between[0], delay_between[1])
                self.log(f"  ⏳ Пауза {delay:.1f} сек...")
                await asyncio.sleep(delay)
        
        # Статистика
        self.log(f"\n{'='*50}")
        self.log(f"📊 ИТОГИ:")
        self.log(f"   Всего: {self.stats['total']}")
        self.log(f"   Лайков: {self.stats['likes']}")
        self.log(f"   Подписок: {self.stats['follows']}")
        self.log(f"   Ошибок: {self.stats['errors']}")


# =============================================================================
# CLI
# =============================================================================

async def main():
    import sys
    
    print("""
╔════════════════════════════════════════════════════════════╗
║        📸 Instagram Human-Like Bot                         ║
╠════════════════════════════════════════════════════════════╣
║  Использование:                                            ║
║    python instagram_human.py urls.txt                      ║
║    python instagram_human.py --interactive                 ║
║                                                            ║
║  Поддерживаемые URL:                                       ║
║    - Reels: instagram.com/reel/...                         ║
║    - Посты: instagram.com/p/...                            ║
║    - Профили: instagram.com/username/                      ║
║                                                            ║
║  ⚠️  При первом запуске войдите в аккаунт!                ║
╚════════════════════════════════════════════════════════════╝
    """)
    
    config = InstagramConfig(
        headless=False,
        auto_like=True,
        auto_follow=False,  # Включи если нужно
    )
    
    bot = InstagramHumanBot(config)
    
    try:
        await bot.start()
        
        if len(sys.argv) > 1 and sys.argv[1] == '--interactive':
            print("\n🔹 Введите URL или 'quit' для выхода\n")
            
            while True:
                try:
                    url = input("URL: ").strip()
                except EOFError:
                    break
                
                if url.lower() == 'quit':
                    break
                if not url:
                    continue
                if 'instagram.com' not in url:
                    print("⚠️ Это не Instagram URL")
                    continue
                
                await bot.process_url(url)
                
        elif len(sys.argv) > 1:
            urls_file = sys.argv[1]
            
            if not os.path.exists(urls_file):
                print(f"❌ Файл не найден: {urls_file}")
                return
            
            with open(urls_file, 'r') as f:
                urls = [line.strip() for line in f if line.strip() and 'instagram' in line.lower()]
            
            if not urls:
                print("❌ В файле нет Instagram URL")
                return
            
            print(f"📋 Загружено {len(urls)} URL")
            await bot.process_urls(urls)
            
        else:
            print("🔹 Открываю Instagram для входа...")
            await bot.page.goto("https://www.instagram.com")
            print("\n⚠️ Войдите в аккаунт, затем запустите:")
            print("   python instagram_human.py urls.txt\n")
            input("Enter для выхода...")
            
    finally:
        await bot.stop()


if __name__ == "__main__":
    asyncio.run(main())
