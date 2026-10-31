Pomůcka pro prostředí bez Android SDK – viz hlavička `check.sh`. Stuby jsou záměrně shovívavé; chyba, kterou zachytí, je skutečná
chyba v Kotlinu nebo ve volání do vlastních modulů, ale „0 chyb“ neznamená, že se aplikace sestaví.
(Při jejím použití vyšla najevo např. chyba typů `Double`/`Float` v `RouteCanvas`.)
